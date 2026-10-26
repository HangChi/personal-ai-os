package com.personalos.chat.service;

import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.PromptContributor;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.api.ErrorCode;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
    private static final String EVENT_CITATIONS = "citations";

    private final AiChatClientProvider aiProvider;
    private final ChatSessionService sessionService;
    private final AiProperties aiProperties;
    private final ObjectProvider<PromptContributor> promptContributors;
    /** 知识库检索窄接口（阶段 19）。用 ObjectProvider：knowledge 模块的 bean 不在时降级为普通对话 */
    private final ObjectProvider<KnowledgeSearchPort> knowledgeSearch;
    private final ChatCitationService citationService;

    public ChatCompletionService(AiChatClientProvider aiProvider,
                                 ChatSessionService sessionService,
                                 AiProperties aiProperties,
                                 ObjectProvider<PromptContributor> promptContributors,
                                 ObjectProvider<KnowledgeSearchPort> knowledgeSearch,
                                 ChatCitationService citationService) {
        this.aiProvider = aiProvider;
        this.sessionService = sessionService;
        this.aiProperties = aiProperties;
        this.promptContributors = promptContributors;
        this.knowledgeSearch = knowledgeSearch;
        this.citationService = citationService;
    }

    /**
     * 发起一次流式对话（不带知识库增强）。
     *
     * <p>保留此重载：阶段 1~18 的调用方与测试无需改动，行为逐字符一致。
     */
    public SseEmitter stream(String conversationId, String userContent, String turnId) {
        return stream(conversationId, userContent, turnId, false, StreamChatRequest.DEFAULT_TOP_K);
    }

    /**
     * 发起一次流式对话（阶段 19：可选知识库增强）。
     *
     * <p><b>顺序很重要</b>：所有前置校验都在返回 emitter **之前**完成。
     * 这样「会话不存在」「参数非法」「模型未配置」得到的是普通 JSON 错误响应
     * （404 / 400 / 503），而不是一个已经切成 {@code text/event-stream}
     * 却只推了一条错误事件的半截流 —— 后者会让客户端难以区分失败原因。
     *
     * <p><b>知识库检索不在这个线程上跑</b>（2026-10-26 调整）：检索要调一次嵌入模型，
     * 是几百毫秒级的阻塞调用。放在这里会让「首字节」被它拖住，Tomcat 线程也一直被占。
     * 现在它被包进 {@link Flux#defer} 并切到弹性线程池：本方法发出 {@code start}
     * 后立即返回，检索与生成都在后台完成。事件顺序不变
     * （{@code start → citations → delta* → done}），仍是「先给依据、再出内容」。
     *
     * @param conversationId 会话标识（隔离边界）
     * @param userContent    本条用户消息
     * @param turnId         本轮标识。客户端可用 {@code Idempotency-Key} 头传入；
     *                       它同时是工具层幂等的范围边界 —— 客户端重试整个请求时，
     *                       若复用同一个键，模型重复发出的工具调用就不会二次执行
     * @param useKnowledge   是否启用知识库增强（先检索再回答，回答带 [n] 引用）
     * @param topK           检索条数（已由调用方收敛到 1~20）
     * @return SSE 发射器
     */
    public SseEmitter stream(String conversationId, String userContent, String turnId,
                             boolean useKnowledge, int topK) {
        sessionService.requireSession(conversationId);
        ChatClient chatClient = aiProvider.requireChatClient();
        // 必须在消息落库之前调用，否则会被「已有消息」的条件挡掉
        sessionService.initializeTitleFromFirstMessage(conversationId, userContent);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        StringBuilder fullContent = new StringBuilder();
        // 终止标记：保证 done / error 只发一次，并让 onCompletion 能区分
        // 「正常结束」与「客户端断连」
        AtomicBoolean terminated = new AtomicBoolean(false);
        // 命中快照：检索在 defer 里才发生，终帧回调需要读它，故用原子引用跨阶段传递
        AtomicReference<List<KnowledgeHitDto>> hitsRef = new AtomicReference<>(List.of());
        // 订阅可能先于赋值完成（切了线程池之后尤其如此），回调里必须能容忍 null
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        send(emitter, EVENT_START, new ChatStreamEvents.Start(conversationId));

        Flux<String> contentFlux = Flux.defer(() -> {
            List<KnowledgeHitDto> hits = retrieveForPrompt(conversationId, userContent, topK, useKnowledge, emitter);
            hitsRef.set(hits);
            return chatClient.prompt()
                    // 请求级 system **替换** defaultSystem（Spring AI 不做拼接），
                    // 所以这里必须显式带上基础人设，否则只传记忆块会让助手「变了个样」且不报错。
                    .system(systemPromptWithContributions(hits))
                    .user(userContent)
                    // 会话标识通过 advisor 参数传入，Advisor 据此装载并回写该会话的上下文
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    // 工具调用时需要的上下文：工具方法通过 ToolContext 参数读取
                    .toolContext(Map.of(
                            ToolContextKeys.CONVERSATION_ID, conversationId,
                            ToolContextKeys.TURN_ID, turnId))
                    .stream()
                    .content();
        }).subscribeOn(Schedulers.boundedElastic());

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
                    // 引用挂载：Advisor 的 after()（落库）位于本回调的上游（见
                    // ChatCitationService.attachToLatestAssistant 的时序说明），
                    // 此刻最新助手消息就是本轮刚落库的那条。
                    List<KnowledgeHitDto> hits = hitsRef.get();
                    if (hits != null && !hits.isEmpty()) {
                        try {
                            citationService.attachToLatestAssistant(conversationId, hits);
                        } catch (RuntimeException ex) {
                            log.warn("引用挂载失败（不影响本轮回答） conversationId={}", conversationId, ex);
                        }
                    }
                });
        subscriptionRef.set(subscription);

        emitter.onTimeout(() -> {
            terminated.set(true);
            disposeQuietly(subscriptionRef.get());
            emitter.complete();
        });
        emitter.onError(throwable -> {
            terminated.set(true);
            disposeQuietly(subscriptionRef.get());
        });
        emitter.onCompletion(() -> {
            // 只有「客户端提前断连」才会走到这里且 terminated 仍为 false；
            // 此时取消上游订阅，避免继续消耗模型额度
            if (!terminated.get()) {
                disposeQuietly(subscriptionRef.get());
            }
        });

        return emitter;
    }

    /**
     * 检索本轮的知识库片段，并把结果（或降级提示）作为 SSE 事件先发出去。
     *
     * <p>调用发生在 {@link Flux#defer} 内部 —— 也就是订阅之后、生成之前，
     * 因此不会阻塞 servlet 线程。失败只降级不中断：检索挂了不该让整轮对话失败（FR-005）。
     *
     * @return 命中片段；未开启增强、端口缺失或检索失败时为空列表
     */
    private List<KnowledgeHitDto> retrieveForPrompt(String conversationId, String userContent,
                                                    int topK, boolean useKnowledge, SseEmitter emitter) {
        if (!useKnowledge) {
            return List.of();
        }
        List<KnowledgeHitDto> hits = List.of();
        KnowledgeSearchPort port = knowledgeSearch.getIfAvailable();
        if (port == null) {
            log.warn("知识库增强开启但检索端口不可用，本轮降级为普通对话 conversationId={}", conversationId);
        } else {
            try {
                hits = port.search(userContent, topK);
            } catch (RuntimeException ex) {
                log.warn("知识库检索失败，本轮降级 conversationId={}", conversationId, ex);
                send(emitter, EVENT_ERROR,
                        new ChatStreamEvents.Error(ErrorCode.AI_UPSTREAM_ERROR.getCode(),
                                "知识库检索失败，本轮未使用知识库回答"));
                return List.of();
            }
        }
        // 检索结果先于生成内容告知用户：N=0 也发（前端据此关闭「正在检索」态）
        List<ChatCitationDto> payload = toCitationDtos(hits);
        send(emitter, EVENT_CITATIONS, new ChatStreamEvents.Citations(payload.size(), payload));
        return hits;
    }

    /** 取消上游订阅；null（订阅尚未建立）与重复取消都视为正常。 */
    private void disposeQuietly(Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 拼装本轮请求的系统提示：基础人设 + 各模块贡献的上下文 + （可选）知识库检索片段。
     *
     * <p>为什么不用 {@code defaultSystem}：它只是**默认值**，请求级 {@code system()} 会替换它。
     * 若只传贡献内容（如记忆块），阶段 1 定下的人设与行为约束会全部丢失 ——
     * 这类错误不报错，只会让助手"变了个样"，极难从日志发现。
     *
     * <p>贡献者为空或返回空串时**不追加任何内容**（连空行都不加），
     * 保证「没有任何上下文」时的系统提示与阶段 1 逐字符相同。
     *
     * <p>知识库片段（阶段 19）追加在最后：编号从 [1] 开始，与 citations 事件、
     * 落库快照三处编号一致；附带使用约束（引用编号、无依据明说、不得编造）。
     */
    private String systemPromptWithContributions(List<KnowledgeHitDto> hits) {
        StringBuilder prompt = new StringBuilder(aiProperties.systemPrompt());
        promptContributors.orderedStream().forEach(contributor -> {
            String contribution = contributor.contribute();
            if (contribution != null && !contribution.isBlank()) {
                prompt.append("\n\n").append(contribution);
            }
        });
        if (hits != null && !hits.isEmpty()) {
            prompt.append("\n\n").append(buildKnowledgeSection(hits));
        }
        return prompt.toString();
    }

    /** 知识库检索片段的系统提示区块（格式即契约，见 contracts C-01）。 */
    private String buildKnowledgeSection(List<KnowledgeHitDto> hits) {
        StringBuilder section = new StringBuilder();
        section.append("以下内容来自用户的个人知识库检索结果（本轮实时检索）：");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHitDto hit = hits.get(i);
            section.append("\n\n[").append(i + 1).append("] 文档《")
                    .append(nullToDash(hit.documentTitle())).append("》（")
                    .append(nullToDash(hit.filename())).append("，第 ")
                    .append(hit.chunkIndex() + 1).append(" 段）\n")
                    .append(hit.content());
        }
        section.append("\n\n使用约束：\n")
                .append("- 回答必须基于上述内容，引用处标注对应编号，如 [1]\n")
                .append("- 上述内容没有答案时，明确说明「知识库中没有找到相关内容」，不得编造\n")
                .append("- 不要复述整段原文，提炼回答");
        return section.toString();
    }

    /** 检索命中 → citations 事件 / 落库共用的 DTO（编号从 1 开始）。 */
    private List<ChatCitationDto> toCitationDtos(List<KnowledgeHitDto> hits) {
        List<ChatCitationDto> dtos = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHitDto hit = hits.get(i);
            dtos.add(new ChatCitationDto(
                    i + 1,
                    hit.documentId().toString(),
                    hit.documentTitle(),
                    hit.filename(),
                    hit.chunkIndex(),
                    hit.content()));
        }
        return dtos;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "未命名" : value;
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
