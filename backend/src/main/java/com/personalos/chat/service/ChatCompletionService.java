package com.personalos.chat.service;

import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 流式对话编排：把模型的 {@code Flux<String>} 桥接为 SSE 事件流。
 *
 * <p>不引入 WebFlux —— 项目已是 Spring Web MVC，为单个端点切双栈违反「简单优先」。
 * `SseEmitter` 是 MVC 下推 SSE 的标准做法，而 {@code Flux} 由 Spring AI 的传递依赖提供。
 *
 * <p>事件契约见 {@code specs/002-ai-chat/contracts/chat-api.md}：
 * {@code start} → {@code delta}* → {@code done}；失败时 {@code error}。
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    /** SSE 超时（毫秒）。超过即关闭连接，避免连接悬挂。
     *  注意：这是「整个流的存活时间上限」，不是首字节超时。 */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    private static final String EVENT_START = "start";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    private final AiChatClientProvider aiProvider;
    private final ChatSessionService sessionService;

    public ChatCompletionService(AiChatClientProvider aiProvider, ChatSessionService sessionService) {
        this.aiProvider = aiProvider;
        this.sessionService = sessionService;
    }

    /**
     * 发起一次流式对话。
     *
     * <p><b>顺序很重要</b>：所有前置校验都在返回 emitter **之前**完成。
     * 这样「会话不存在」「参数非法」「模型未配置」得到的是普通 JSON 错误响应
     * （404 / 400 / 503），而不是一个已经切成 {@code text/event-stream}
     * 却只推了一条错误事件的半截流 —— 后者会让客户端难以区分失败原因。
     *
     * @param conversationId 会话标识（隔离边界）
     * @param userContent    本条用户消息
     * @return SSE 发射器
     */
    public SseEmitter stream(String conversationId, String userContent) {
        sessionService.requireSession(conversationId);
        ChatClient chatClient = aiProvider.requireChatClient();
        // 必须在消息落库之前调用，否则会被「已有消息」的条件挡掉
        sessionService.initializeTitleFromFirstMessage(conversationId, userContent);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        StringBuilder fullContent = new StringBuilder();
        // 终止标记：保证 done / error 只发一次，并让 onCompletion 能区分
        // 「正常结束」与「客户端断连」
        AtomicBoolean terminated = new AtomicBoolean(false);

        send(emitter, EVENT_START, new ChatStreamEvents.Start(conversationId));

        Flux<String> contentFlux = chatClient.prompt()
                .user(userContent)
                // 会话标识通过 advisor 参数传入，Advisor 据此装载并回写该会话的上下文
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();

        Disposable subscription = contentFlux.subscribe(
                chunk -> {
                    if (terminated.get() || chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    fullContent.append(chunk);
                    send(emitter, EVENT_DELTA, new ChatStreamEvents.Delta(chunk));
                },
                error -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    log.warn("流式对话失败 conversationId={}", conversationId, error);
                    ErrorCode errorCode = classify(error);
                    send(emitter, EVENT_ERROR,
                            new ChatStreamEvents.Error(errorCode.getCode(), errorCode.getDefaultMessage()));
                    emitter.complete();
                },
                () -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    send(emitter, EVENT_DONE,
                            new ChatStreamEvents.Done(conversationId, fullContent.toString()));
                    emitter.complete();
                });

        emitter.onTimeout(() -> {
            terminated.set(true);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(throwable -> {
            terminated.set(true);
            subscription.dispose();
        });
        emitter.onCompletion(() -> {
            // 只有「客户端提前断连」才会走到这里且 terminated 仍为 false；
            // 此时取消上游订阅，避免继续消耗模型额度
            if (!terminated.get()) {
                subscription.dispose();
            }
        });

        return emitter;
    }

    /**
     * 发送一个 SSE 事件。
     *
     * <p>发送失败（客户端已断开 / emitter 已结束）只记日志，不向上抛 ——
     * 否则会污染全局异常处理，把「客户端走了」误报成服务端错误。
     */
    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE 事件发送失败，客户端可能已断开：event={}", eventName, ex);
        }
    }

    /**
     * 把上游异常归类为业务错误码。
     *
     * <p>只依据异常类型判断，**不把上游原始报文透出**（FR-012）。
     */
    private ErrorCode classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IOException) {
                return ErrorCode.AI_UPSTREAM_ERROR;
            }
            String className = current.getClass().getName();
            if (className.startsWith("org.springframework.web.client")
                    || className.startsWith("org.springframework.web.reactive.function.client")
                    || className.startsWith("org.springframework.ai.retry")
                    || className.startsWith("org.springframework.ai.openai")
                    || className.startsWith("org.springframework.ai.ollama")) {
                return ErrorCode.AI_UPSTREAM_ERROR;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
