package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.dto.ThinkingLevel;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.PromptContributor;
import com.personalos.common.ai.PostTurnContributor;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.web.RequestIdFilter;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.chat.metrics.ChatStreamMetrics;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.domain.PageRequest;
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

    /**
     * SSE 心跳间隔（毫秒）。
     *
     * <h2>为什么必须有心跳</h2>
     * 思考型模型在首 token 之前可能静默 60 秒以上，而链路上的每一跳都有自己的
     * 空闲判定：nginx {@code proxy_read_timeout} 默认 60 秒、部分云负载均衡更短。
     * 一旦触发，连接被单方面掐断，而**服务端不会收到任何错误** —— 日志显示一切正常，
     * 实际表现却是「流在某处莫名中断」或「前端一直转圈」。
     *
     * <p>心跳把「有没有数据」与「模型有没有产出」解耦：只要连接还在，就一直在写字节。
     */
    private static final long HEARTBEAT_INTERVAL_MILLIS = 15_000L;

    /**
     * 心跳调度器。
     *
     * <p>不复用 Reactor 的 {@code boundedElastic}：那是给「算数据」的，线程数有限，
     * 而心跳要贯穿整个流（最长 120 秒）。占着弹性池的线程只为写一行注释帧，不划算。
     *
     * <p>直接用 JDK 的守护线程池：进程退出时无需额外关闭，也不必为一个心跳
     * 引入 Spring {@code TaskScheduler} 的生命周期管理。单用户场景下同时活跃的流
     * 通常只有一个，两个线程足以覆盖「上一个流正在收尾」的窗口。
     */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

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
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentService attachmentService;
    private final ChatSummaryService summaryService;
    /** 轮末钩子（记忆自动沉淀等）：通过 SPI 收集，chat 不认识具体业务模块 */
    private final ObjectProvider<PostTurnContributor> postTurnContributors;
    /** 活跃流数指标：SSE 请求不会快速结束，HTTP 指标看不出「卡住」，只能靠它 */
    private final ChatStreamMetrics metrics;

    public ChatCompletionService(AiChatClientProvider aiProvider,
                                 ChatSessionService sessionService,
                                 AiProperties aiProperties,
                                 ObjectProvider<PromptContributor> promptContributors,
                                 ObjectProvider<KnowledgeSearchPort> knowledgeSearch,
                                 ChatCitationService citationService,
                                 ChatSummaryService summaryService,
                                 ObjectProvider<PostTurnContributor> postTurnContributors,
                                 ChatMessageRepository messageRepository,
                                 ChatAttachmentService attachmentService,
                                 ChatStreamMetrics metrics) {
        this.aiProvider = aiProvider;
        this.sessionService = sessionService;
        this.aiProperties = aiProperties;
        this.promptContributors = promptContributors;
        this.knowledgeSearch = knowledgeSearch;
        this.citationService = citationService;
        this.summaryService = summaryService;
        this.postTurnContributors = postTurnContributors;
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
        this.metrics = metrics;
    }

    /**
     * 发起一次流式对话（不带知识库增强）。
     *
     * <p>保留此重载：阶段 1~18 的调用方与测试无需改动，行为逐字符一致。
     */
    public SseEmitter stream(String conversationId, String userContent, String turnId) {
        return stream(conversationId, userContent, turnId, false, StreamChatRequest.DEFAULT_TOP_K,
                ThinkingLevel.OFF, List.of());
    }

    /**
     * 发起一次流式对话（阶段 19：可选知识库增强；阶段 22：可选思考程度）。
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
                             boolean useKnowledge, int topK, ThinkingLevel thinkingLevel,
                             List<ChatAttachmentDto> requestAttachments) {
        // 先把 requestId 抓成局部变量：回调可能跑在第四个、第五个池化线程上，
        // 那时请求线程早被回收、MDC 也清空了。见 runWithRequestId 的说明。
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
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
        // 本轮上下文字符数（仪表盘用）：在 defer 里算，终帧回调里读
        AtomicInteger promptCharsRef = new AtomicInteger(0);
        // 订阅可能先于赋值完成（切了线程池之后尤其如此），回调里必须能容忍 null
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        send(emitter, EVENT_START, new ChatStreamEvents.Start(conversationId));
        // 计入活跃流：从这一刻起，模型可能一直不出字，HTTP 指标却是安静的白板，
        // 只有这个数字能说明「有一条流在跑」
        metrics.streamOpened();
        // 终止路径有四条（done / error / 超时 / 客户端断开），彼此可能重叠触发，
        // 用独立的原子标记保证活跃数只减一次 —— 负数比不准确更糟，它会让人以为指标坏了
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        Runnable closeStream = () -> {
            if (streamClosed.compareAndSet(false, true)) {
                metrics.streamClosed();
            }
        };
        // 心跳紧跟 start：模型的静默期正是从这一刻开始的，
        // 晚一步启动就等于把「首 token 之前」这段最长的空窗暴露给代理的空闲判定。
        ScheduledFuture<?> heartbeat = startHeartbeat(emitter);

        Flux<String> contentFlux = Flux.defer(() -> {
            List<KnowledgeHitDto> hits = retrieveForPrompt(conversationId, userContent, topK, useKnowledge, emitter);
            hitsRef.set(hits);
            String systemPrompt = systemPromptWithContributions(hits, userContent, conversationId);
            // 上下文用量：系统提示 + 本条用户消息。
            // 窗口内的历史由 Advisor 在请求链路里装载，这里拿不到原文，
            // 因此用「窗口条数 × 已存消息的平均长度」估算并在事件里如实标注为估算值。
            int historyChars = estimateHistoryChars(conversationId);
            promptCharsRef.set(systemPrompt.length() + userContent.length() + historyChars);
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    // 请求级 system **替换** defaultSystem（Spring AI 不做拼接），
                    // 所以这里必须显式带上基础人设，否则只传记忆块会让助手「变了个样」且不报错。
                    .system(systemPrompt)
                    .user(userContent)
                    // 会话标识通过 advisor 参数传入，Advisor 据此装载并回写该会话的上下文
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    // 工具调用时需要的上下文：工具方法通过 ToolContext 参数读取
                    .toolContext(Map.of(
                            ToolContextKeys.CONVERSATION_ID, conversationId,
                            ToolContextKeys.TURN_ID, turnId));

            // 思考程度（阶段 22）：由 provider 门面在模型默认选项上合并，
            // 非 OpenAI 系模型时原样返回，此处无需判断 provider 类型。
            request = aiProvider.applyThinkingOptions(request, thinkingLevel.budget());

            return request
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
                error -> runWithRequestId(requestId, () -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    stopHeartbeat(heartbeat);
                    closeStream.run();
                    log.warn("流式对话失败 conversationId={}", conversationId, error);
                    ErrorCode errorCode = classify(error);
                    send(emitter, EVENT_ERROR,
                            new ChatStreamEvents.Error(errorCode.getCode(), errorCode.getDefaultMessage()));
                    emitter.complete();
                }),
                () -> runWithRequestId(requestId, () -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    // 心跳先停：后续的落库与钩子都在同一个线程里串行执行，
                    // 期间继续发注释帧只会让「已经结束了」的连接多撑几秒。
                    stopHeartbeat(heartbeat);
                    closeStream.run();
                    // 引用挂载必须**先于** done 事件（2026-10-26 调整）。
                    // 原先顺序是「先发 done，再挂引用」，而两者在同一条回调里顺序执行：
                    // 客户端一收到 done 就可能认为本轮全部结束并去查库，此时引用还没提交 ——
                    // 集成测试因此间歇性看到 0 条引用（CI 上先炸，本地时序宽松看不出来）。
                    // 调整后 done 的语义变成「本轮该落的库都落完了」，对客户端也更强。
                    List<KnowledgeHitDto> hits = hitsRef.get();
                    if (hits != null && !hits.isEmpty()) {
                        try {
                            citationService.attachToLatestAssistant(conversationId, hits);
                        } catch (RuntimeException ex) {
                            log.warn("引用挂载失败（不影响本轮回答） conversationId={}", conversationId, ex);
                        }
                    }

                    send(emitter, EVENT_DONE,
                            new ChatStreamEvents.Done(conversationId, fullContent.toString(),
                                    ContextBudgetService.toTokens(promptCharsRef.get())));
                    emitter.complete();

                    // 附件挂载（阶段 22）：附件属于用户发的那条消息，而它由 Advisor 落库，
                    // 因此只能在此刻补写 attachments 列 —— 与引用挂载同一时机、同一些约束。
                    List<ChatAttachmentDto> attachments = attachmentService.verify(requestAttachments);
                    if (!attachments.isEmpty()) {
                        try {
                            messageRepository
                                    .findFirstByConversationIdAndMessageTypeOrderBySequenceNoDesc(
                                            conversationId, MessageRole.USER)
                                    .ifPresent(message -> {
                                        message.setAttachments(attachmentService.toJson(attachments));
                                        messageRepository.save(message);
                                    });
                        } catch (RuntimeException ex) {
                            log.warn("附件挂载失败（不影响本轮回答） conversationId={}", conversationId, ex);
                        }
                    }

                    // 轮末动作（阶段 22）：摘要刷新与记忆沉淀都只影响**下一轮**，
                    // 因此一律异步提交，绝不让它们拖住本轮的收尾。
                    String reply = fullContent.toString();
                    summaryService.refreshAsync(conversationId);
                    postTurnContributors.orderedStream().forEach(contributor -> {
                        try {
                            contributor.afterTurn(conversationId, userContent, reply);
                        } catch (RuntimeException ex) {
                            // SPI 实现若同步抛错，不能影响已经产生的回答
                            log.warn("轮末钩子执行失败 contributor={} conversationId={}",
                                    contributor.getClass().getSimpleName(), conversationId, ex);
                        }
                    });
                }));
        subscriptionRef.set(subscription);

        emitter.onTimeout(() -> {
            terminated.set(true);
            stopHeartbeat(heartbeat);
            closeStream.run();
            disposeQuietly(subscriptionRef.get());
            emitter.complete();
        });
        emitter.onError(throwable -> {
            terminated.set(true);
            stopHeartbeat(heartbeat);
            closeStream.run();
            disposeQuietly(subscriptionRef.get());
        });
        emitter.onCompletion(() -> {
            // 只有「客户端提前断连」才会走到这里且 terminated 仍为 false；
            // 此时取消上游订阅，避免继续消耗模型额度
            stopHeartbeat(heartbeat);
            closeStream.run();
            if (!terminated.get()) {
                disposeQuietly(subscriptionRef.get());
            }
        });

        return emitter;
    }

    /**
     * 估算窗口内历史的字符数（供上下文仪表盘）。
     *
     * <p>为什么是估算：历史由 {@code MessageChatMemoryAdvisor} 在请求链路里装载，
     * 这个方法拿不到最终窗口的原文。用「该会话全部消息的总字符 ÷ 消息条数 × 窗口条数」
     * 逼近，误差来自「窗口只取尾部」与「消息长短不均」。
     *
     * <p>仪表盘是给用户看趋势的，不是计费用途 —— 所以估算够用，
     * 且不值得为它把整条请求链路改成可观测。
     */
    /**
     * 估算某个会话下一次请求的上下文字符数（仪表盘用）。
     *
     * <p>与流内计算同源，但独立可调：刷新页面后前端拿不到 done 事件，
     * 需要单独查一次才能显示仪表盘。
     */
    public int estimatePromptChars(String conversationId) {
        // 只含系统提示 + 窗口内历史：窗口里已经包含最新的那些消息，
        // 再单独加一条「最新消息长度」就是重复计算（2026-10-26 修）。
        // 下一轮用户要发什么这里不可能知道，所以不计入。
        String systemPrompt = systemPromptWithContributions(List.of(), "", conversationId);
        return systemPrompt.length() + estimateHistoryChars(conversationId);
    }

    /** 仪表盘口径：估算 token 数（字符 ÷ 1.6） */
    public int estimatePromptTokens(String conversationId) {
        return ContextBudgetService.toTokens(estimatePromptChars(conversationId));
    }

    private int estimateHistoryChars(String conversationId) {
        int limit = aiProperties.memory().maxMessages();
        // 取窗口条数上限内最新的若干条，直接求字符和 —— 就是窗口内历史的字符数。
        //
        // 与「条数窗口」完全一致；只有当**字符预算**把窗口裁得更短时，这里会偏大
        // （裁掉的是更早的消息，而我们统计的是最新的 limit 条，通常不会正好是被裁的那条）。
        // 这个偏差方向是「偏大」，对仪表盘是安全的：宁可让用户以为快满了，也不要显示假的宽裕。
        List<ChatMessageEntity> recent = messageRepository
                .findRecentByConversationId(conversationId, PageRequest.of(0, limit));
        return (int) recent.stream()
                .mapToLong(message -> message.getContent() == null ? 0 : message.getContent().length())
                .sum();
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
     * 在日志上下文里执行一段回调，把异步日志与发起它的请求关联起来。
     *
     * <h2>为什么光有调度钩子不够</h2>
     * {@code MdcSchedulerHook} 只能覆盖「订阅时切一次线程」这一跳。而流式的真实路径是
     * HTTP 客户端在自己的线程池里发信号，错误回调可能落在**第三个**池化线程上 ——
     * 那一跳的提交者本身已经没有 MDC，钩子无从捕获。
     * 实测：{@code defer} 内的日志带着 requestId（boundedElastic-1），
     * 而在 boundedElastic-3 上的失败日志就丢了。
     *
     * <p>所以在回调入口显式恢复。用 try/finally 清理：回调跑在池化线程上，
     * 残留会串到下一个请求。
     */
    private void runWithRequestId(String requestId, Runnable action) {
        if (requestId == null || requestId.isBlank()) {
            action.run();
            return;
        }
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        try {
            action.run();
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
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
    /**
     * 拼装本轮请求的系统提示。
     *
     * <p>顺序即优先级：<b>基础人设 → 早前对话摘要 → 各模块上下文（长期记忆等）→ 知识库片段</b>。
     * 摘要紧跟人设，是因为它回答「这段对话之前发生了什么」，属于对话本身的一部分；
     * 记忆与检索则是外部知识，排在后面。
     *
     * <p>为什么不用 {@code defaultSystem}：它只是**默认值**，请求级 {@code system()} 会替换它。
     * 若只传贡献内容（如记忆块），阶段 1 定下的人设与行为约束会全部丢失 ——
     * 这类错误不报错，只会让助手"变了个样"，极难从日志发现。
     *
     * <p>各段为空时**不追加任何内容**（连空行都不加），保证「没有任何上下文」时的系统提示
     * 与阶段 1 逐字符相同。
     */
    private String systemPromptWithContributions(List<KnowledgeHitDto> hits, String userInput,
                                                 String conversationId) {
        StringBuilder prompt = new StringBuilder(aiProperties.systemPrompt());

        String summary = summaryService.renderForConversation(conversationId);
        if (!summary.isBlank()) {
            prompt.append("\n\n").append(summary);
        }

        promptContributors.orderedStream().forEach(contributor -> {
            // 传入本轮问题：贡献者据此做相关性筛选（如长期记忆按相关度而非纯时间排序）
            String contribution = contributor.contribute(userInput);
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
     * 启动心跳：按固定间隔发送 SSE 注释帧。
     *
     * <p>用 {@code comment} 而不是自定义事件：注释帧在 SSE 规范里本来就被客户端
     * **忽略**，因此前端无需任何改动即保持兼容，也不会混进
     * {@code start → citations → delta* → done} 的事件序列里。
     *
     * <p>发送失败只降级为 debug 日志：客户端断开时写响应必然失败，那是正常结束路径之一，
     * 不该被当成错误刷 warn。真正的清理在各终止回调里完成。
     *
     * @return 可取消的句柄，由各终止路径交给 {@link #stopHeartbeat}
     */
    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter) {
        return HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                log.debug("SSE 心跳发送失败，客户端可能已断开", ex);
            }
        }, HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** 停止心跳；null 与重复取消都视为正常（多条终止路径可能同时触发）。 */
    private void stopHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
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
