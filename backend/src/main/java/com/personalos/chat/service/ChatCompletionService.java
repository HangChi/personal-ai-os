package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.dto.ThinkingLevel;
import com.personalos.chat.metrics.ChatStreamMetrics;
import com.personalos.chat.support.ChatResponseInspection;
import com.personalos.chat.support.SseHeartbeatScheduler;
import com.personalos.chat.support.StreamUsage;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.web.RequestIdFilter;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * {@code SseEmitter} 是 MVC 下推 SSE 的标准做法，而 {@code Flux} 由 Spring AI 的传递依赖提供。
 *
 * <p>事件契约见 {@code specs/002-ai-chat/contracts/chat-api.md}：
 * {@code start} → {@code citations} → {@code delta}* → {@code done}；失败时 {@code error}。
 *
 * <h2>职责边界（P2 重构后）</h2>
 * 本类只做**编排**：校验、建流、转发增量、在四种终止路径上做清理。三块相对独立的能力
 * 已经拆出去，它们各自有独立的变更原因：
 * <ul>
 *   <li>{@link ChatPromptAssembler} —— 送给模型的系统提示长什么样；</li>
 *   <li>{@link ChatTurnFinisher} —— 流完了往库里写什么、按什么顺序写；</li>
 *   <li>{@link SseHeartbeatScheduler} —— 怎么让连接不被中间设备判定为空闲。</li>
 * </ul>
 * 拆之前这个类 500 多行、11 个构造依赖，每加一个横切关注点（心跳、指标、MDC）都往里堆。
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    /**
     * 整条流的存活时间上限（「深度思考」档会翻倍，见 {@link #timeoutFor}）。
     *
     * <p>可配置是为了让超时路径能被测试；线上不需要调它。
     */
    private final Duration baseStreamTimeout;

    private static final String EVENT_START = "start";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    private static final String EVENT_CITATIONS = "citations";

    private final AiChatClientProvider aiProvider;
    private final ChatSessionService sessionService;
    private final ChatPromptAssembler promptAssembler;
    /** 知识库检索窄接口（阶段 19）。用 ObjectProvider：knowledge 模块的 bean 不在时降级为普通对话 */
    private final ObjectProvider<KnowledgeSearchPort> knowledgeSearch;
    private final ChatTurnFinisher finisher;
    /** 活跃流数指标：SSE 请求不会快速结束，HTTP 指标看不出「卡住」，只能靠它 */
    private final ChatStreamMetrics metrics;
    private final SseHeartbeatScheduler heartbeatScheduler;
    /** 把本轮附件转成模型能读的内容（图片多模态 / 文本区块） */
    private final ChatAttachmentContextBuilder attachmentContextBuilder;
    /** 用量记账（清单第 7 项）：异步写，失败不影响回答 */
    private final TokenUsageRecorder usageRecorder;

    public ChatCompletionService(AiChatClientProvider aiProvider,
                                 ChatSessionService sessionService,
                                 ChatPromptAssembler promptAssembler,
                                 ObjectProvider<KnowledgeSearchPort> knowledgeSearch,
                                 ChatTurnFinisher finisher,
                                 ChatStreamMetrics metrics,
                                 SseHeartbeatScheduler heartbeatScheduler,
                                 ChatAttachmentContextBuilder attachmentContextBuilder,
                                 @Value("${app.chat.stream-timeout:120s}") Duration baseStreamTimeout,
                                 TokenUsageRecorder usageRecorder) {
        this.aiProvider = aiProvider;
        this.sessionService = sessionService;
        this.promptAssembler = promptAssembler;
        this.knowledgeSearch = knowledgeSearch;
        this.finisher = finisher;
        this.metrics = metrics;
        this.heartbeatScheduler = heartbeatScheduler;
        this.attachmentContextBuilder = attachmentContextBuilder;
        this.baseStreamTimeout = baseStreamTimeout;
        this.usageRecorder = usageRecorder;
    }

    /**
     * 按思考深度决定这条流能活多久。
     *
     * <p>固定 120 秒对「深度思考」是不够的：8192 的思考预算在思考型模型上，
     * 光是生成 reasoning 就可能超过两分钟 —— 用户看到的是「等到一半连接就断了」。
     * 而关掉思考的普通问答并不需要更长的窗口，给它同样的额度只是让卡死的连接多挂两分钟。
     */
    private long timeoutFor(ThinkingLevel thinkingLevel) {
        long base = baseStreamTimeout.toMillis();
        return thinkingLevel == ThinkingLevel.DEEP ? base * 2 : base;
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

        long streamTimeout = timeoutFor(thinkingLevel);
        // emitter 自带的超时只作兜底：它比我们自己的看门狗晚 5 秒。
        // 真正给客户端发 error 帧的是下面那个 scheduledTimeout ——
        // servlet 异步超时一旦触发，容器已经判定响应结束，再写什么都到不了客户端。
        SseEmitter emitter = new SseEmitter(streamTimeout + 5_000L);
        StringBuilder fullContent = new StringBuilder();
        // 终止标记：保证 done / error 只发一次，并让 onCompletion 能区分
        // 「正常结束」与「客户端断连」
        AtomicBoolean terminated = new AtomicBoolean(false);
        // 命中快照：检索在 defer 里才发生，终帧回调需要读它，故用原子引用跨阶段传递
        AtomicReference<List<KnowledgeHitDto>> hitsRef = new AtomicReference<>(List.of());
        // 本轮上下文字符数（仪表盘用）：在 defer 里算，终帧回调里读
        AtomicInteger promptCharsRef = new AtomicInteger(0);
        // 系统提示单独留一份：估算用量时「提示」与「补全」要分开算 ——
        // 系统提示+历史+用户消息属于 prompt，模型生成的部分属于 completion。
        // 合成一个总数会让统计页的「输入/输出」两列失去意义。
        AtomicInteger systemPromptCharsRef = new AtomicInteger(0);
        // 订阅可能先于赋值完成（切了线程池之后尤其如此），回调里必须能容忍 null
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        // 超时看门狗的句柄（见下面 stopEverything 与 scheduleOnce 处）
        AtomicReference<ScheduledFuture<?>> watchdogRef = new AtomicReference<>();

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
        ScheduledFuture<?> heartbeat = heartbeatScheduler.start(emitter);

        Flux<ChatResponse> contentFlux = Flux.defer(() -> {
            List<KnowledgeHitDto> hits = retrieveForPrompt(conversationId, userContent, topK, useKnowledge, emitter);
            hitsRef.set(hits);
            // 附件：图片随 user message 走（多模态协议要求），文本进系统提示（避免存进历史）。
            // 只影响本轮 —— 见 ChatAttachmentContextBuilder 的类注释。
            ChatAttachmentContext attachmentContext = attachmentContextBuilder.build(requestAttachments);
            String systemPrompt = promptAssembler.assemble(hits, userContent, conversationId,
                    attachmentContext.textBlock());
            // 上下文用量：本轮系统提示 + 本条用户消息 + 窗口内历史（后两项为估算）。
            // 历史由 Advisor 在请求链路里装载，这里拿不到原文，
            // 因此用「窗口条数 × 已存消息的平均长度」逼近并在事件里如实标注为估算值。
            systemPromptCharsRef.set(systemPrompt.length());
            promptCharsRef.set(systemPrompt.length() + userContent.length()
                    + promptAssembler.estimateHistoryChars(conversationId));
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    // 请求级 system **替换** defaultSystem（Spring AI 不做拼接），
                    // 所以这里必须显式带上基础人设，否则只传记忆块会让助手「变了个样」且不报错。
                    .system(systemPrompt)
                    // 图片作为多模态媒体挂在用户消息上；无图片时退化成纯文本（行为与之前一致）
                    .user(user -> {
                        user.text(userContent);
                        if (!attachmentContext.images().isEmpty()) {
                            user.media(attachmentContext.imageArray());
                        }
                    })
                    // 会话标识通过 advisor 参数传入，Advisor 据此装载并回写该会话的上下文
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    // 工具调用时需要的上下文：工具方法通过 ToolContext 参数读取
                    .toolContext(Map.of(
                            ToolContextKeys.CONVERSATION_ID, conversationId,
                            ToolContextKeys.TURN_ID, turnId));

            // 思考程度（阶段 22）：由 provider 门面在模型默认选项上合并，
            // 非 OpenAI 系模型时原样返回，此处无需判断 provider 类型。
            request = aiProvider.applyThinkingOptions(request, thinkingLevel.budget());

            // 用 chatResponse() 而不是 content()：后者内部是
            // "map(取正文) → filter(非空)"，正文之外的元数据（含 usage）在这一步被丢掉。
            // 改成自己映射才能在同一路流上同时拿到正文与用量，不必为统计多发一次请求。
            return request
                    .stream()
                    .chatResponse();
        }).subscribeOn(Schedulers.boundedElastic());

        // 本轮用量：只有流的最后一个块带 usage（OpenAI 协议的 choices 为空块），
        // 因此每块都尝试覆盖，最后那个有效值胜出
        AtomicReference<StreamUsage> usageRef = new AtomicReference<>();

        // ------------------------------------------------------------------ 终止收口
        // 四条终止路径（正常完成 / 上游错误 / 超时 / 客户端断连）此前各写了一段清理，
        // 漏掉一处就是「连接关了但模型还在烧 token」或「用户只看到转圈」。
        // 现在统一成一个收尾动作，只有「怎么告知用户」这一句不同。

        /** 停止一切后续动作：心跳、看门狗、活跃指标、上游订阅。重复调用安全。 */
        Runnable stopEverything = () -> {
            heartbeatScheduler.stop(heartbeat);
            heartbeatScheduler.stop(watchdogRef.get());
            closeStream.run();
            disposeQuietly(subscriptionRef.get());
        };

        /**
         * 失败收尾：先按需发 error 帧，再把已产生的半截回答落库，最后关闭。
         *
         * <p><b>为什么必须落半截回答</b>：用户消息由 Advisor 在订阅时就写了，
         * 而助手消息要等流正常终止才写。失败时若什么都不写，历史里就留下一条
         * 只有提问没有回答的记录 —— 下一轮模型会把它当成「用户在自言自语」，
         * 摘要也会把它当事实压进去。落一条带中断标记的回答，上下文才是自洽的。
         */
        BiConsumer<ErrorCode, Boolean> failWith = (errorCode, notifyClient) -> runWithRequestId(requestId, () -> {
            if (!terminated.compareAndSet(false, true)) {
                return; // 已有别的路径收过尾，不能重复写消息
            }
            stopEverything.run();
            if (notifyClient) {
                // 发送失败（客户端已断开）无需再通知，但收尾必须照做
                send(emitter, EVENT_ERROR,
                        new ChatStreamEvents.Error(errorCode.getCode(), errorCode.getDefaultMessage()));
            }
            finisher.recordInterruptedReply(conversationId, fullContent.toString());
            emitter.complete();
        });

        // 自己的看门狗：到点先发 error 帧再关流。必须在订阅之前就位 ——
        // 模型「连上了但一个字节也不出」正是需要它的场景。
        // 句柄放原子引用里，好让上面的 stopEverything 能引用到它（声明顺序所限）。
        watchdogRef.set(heartbeatScheduler.scheduleOnce(() -> {
            // 先看是否已经收过尾：正常结束路径会主动取消本任务，但「取消」与「已到点」
            // 可能擦肩而过。不判这一下，那条假超时警告仍会偶发出现。
            if (terminated.get()) {
                return;
            }
            log.warn("流式对话超时（{}ms，思考档={}） conversationId={}",
                    streamTimeout, thinkingLevel.code(), conversationId);
            failWith.accept(ErrorCode.AI_TIMEOUT, true);
        }, streamTimeout));

        // 回调必须**先注册再订阅**：客户端在 start 之后立刻断开时，
        // onCompletion 可能早于下面这行 subscribe 的返回 —— 那时 subscriptionRef 还是 null，
        // dispose 变成 no-op，模型白白继续生成到自然结束。
        emitter.onTimeout(() -> {
            // 走到这里说明看门狗没能按时收尾（例如调度线程被打满）。
            // 此时响应已被容器判为结束，只能做清理，发不出任何事件。
            log.warn("SSE 异步请求超时（兜底路径） conversationId={}", conversationId);
            if (terminated.compareAndSet(false, true)) {
                stopEverything.run();
                finisher.recordInterruptedReply(conversationId, fullContent.toString());
            }
            emitter.complete();
        });
        emitter.onError(throwable -> {
            log.warn("SSE 连接异常 conversationId={}", conversationId, throwable);
            failWith.accept(ErrorCode.INTERNAL_ERROR, false);
        });
        emitter.onCompletion(() -> {
            // 正常结束时 terminated 已为 true，这里什么都不做；
            // 只有「客户端提前断连」才会走到这里且仍未终止 —— 此时必须取消上游
            if (!terminated.get()) {
                stopEverything.run();
            }
        });

        Disposable subscription = contentFlux.subscribe(
                response -> {
                    // 用量块（choices 为空）没有正文，但必须在这里被采集 ——
                    // 提前 return 会把它一起跳过，症状是「永远只有估算值」
                    StreamUsage usage = ChatResponseInspection.usageOf(response);
                    if (usage != null) {
                        usageRef.set(usage);
                    }
                    String chunk = ChatResponseInspection.textOf(response);
                    if (terminated.get() || chunk.isEmpty()) {
                        return;
                    }
                    fullContent.append(chunk);
                    // 发送失败 = 客户端已经走了。必须立刻取消上游，
                    // 否则每个 delta 都往死 socket 写一次异常，而模型还在继续烧 token。
                    if (!send(emitter, EVENT_DELTA, new ChatStreamEvents.Delta(chunk))) {
                        terminateQuietly(terminated, stopEverything, "客户端已断开");
                    }
                },
                error -> runWithRequestId(requestId, () -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    stopEverything.run();
                    log.warn("流式对话失败 conversationId={}", conversationId, error);
                    ErrorCode errorCode = classify(error);
                    send(emitter, EVENT_ERROR,
                            new ChatStreamEvents.Error(errorCode.getCode(), errorCode.getDefaultMessage()));
                    finisher.recordInterruptedReply(conversationId, fullContent.toString());
                    emitter.complete();
                }),
                () -> runWithRequestId(requestId, () -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    // 心跳先停：后续的落库与钩子都在同一个线程里串行执行，
                    // 期间继续发注释帧只会让「已经结束了」的连接多撑几秒。
                    heartbeatScheduler.stop(heartbeat);
                    // 看门狗也必须在这里停。漏了这一句的后果不是数据错，
                    // 而是**每条正常结束的对话都会在超时时限之后刷一条假的「流式对话超时」警告**
                    // （2026-10-27 真机日志实测踩到）。虚假告警会让真超时被淹没。
                    heartbeatScheduler.stop(watchdogRef.get());
                    closeStream.run();

                    // 引用挂载必须**先于** done 事件（2026-10-26 调整）：
                    // 客户端一收到 done 就可能认为本轮全部结束并去查库，
                    // 此时引用还没提交就会看到 0 条。详见 ChatTurnFinisher 的类注释。
                    finisher.attachCitations(conversationId, hitsRef.get());

                    String reply = fullContent.toString();
                    // 用量事件的下发与记录（清单第 7 项）。
                    // 有真实 usage 时以它为准，否则退回估算值 —— 但要标出来源，
                    // 不能让前端把估算值当计费口径展示给用户。
                    StreamUsage usage = resolveUsage(usageRef.get(),
                            promptCharsRef.get(), systemPromptCharsRef.get(), reply.length());
                    send(emitter, EVENT_DONE, new ChatStreamEvents.Done(conversationId, reply,
                            usage.promptTokens()));
                    emitter.complete();

                    // 记账放在 complete 之后：统计失败或慢都不该影响「回答已结束」这个事实
                    usageRecorder.record(conversationId, usage);

                    // done 之后：附件补写、摘要刷新、轮末钩子（都只影响下一轮或仅用于展示）
                    finisher.afterDone(conversationId, userContent, reply, requestAttachments);
                }));
        subscriptionRef.set(subscription);
        // 订阅返回前就已被终止（上面任一路径抢先）：这里补一次 dispose，
        // 否则那次 dispose 落在 null 上，等于没取消。
        if (terminated.get()) {
            disposeQuietly(subscription);
        }

        return emitter;
    }

    /**
     * 静默终止：不发任何事件，只做清理。
     *
     * <p>用于「已经无法通知客户端」的场合（连接已断），因此不需要 MDC 也不需要落库 ——
     * 半截回答会在 {@link ChatTurnFinisher#recordInterruptedReply} 由失败路径补写，
     * 而这里连客户端都没有了。
     */
    private void terminateQuietly(AtomicBoolean terminated, Runnable stopEverything, String reason) {
        if (terminated.compareAndSet(false, true)) {
            log.debug("流式对话中止：{}", reason);
            stopEverything.run();
        }
    }

    /**
     * 决定本轮用哪组用量数字。
     *
     * <h2>为什么优先真实值</h2>
     * 估算值是「字符 ÷ 1.6」的折算，而模型计费按真实 token（含思考 token，
     * 那部分在正文里根本看不到）。有真实值却展示估算，等于给用户一个无法对账的数字。
     *
     * <h2>为什么真实值会比「字符估算」大好几倍（实测）</h2>
     * 本地实测一轮短对话：系统提示只有几百字符，但上游返回
     * {@code prompt_tokens = 3071}。差值来自**工具定义**——15 个内置工具的
     * JSON Schema 连同 MCP 工具一起被序列化进请求，这几千 token 在字符估算里
     * 完全不存在。因此：
     * <ul>
     *   <li>有真实值时，仪表盘分母一下子「看起来涨了」是正常的，不是回归；</li>
     *   <li>估算值注定偏低，只能当趋势参考，不能当账单。</li>
     * </ul>
     * 这也说明为什么两者必须标来源 —— 混在一起会让用户以为数字突然错乱。
     *
     * <h2>什么时候只能估算</h2>
     * provider 不返回 usage（ollama 原生接口、联调 mock）或忽略
     * {@code include_usage}。这时按 promptChars / 回复字符数折算，并把来源标成
     * {@link com.personalos.chat.entity.TokenUsageSource#ESTIMATED}。
     * 注意 promptChars 里已经包含系统提示（附件与知识库都在其中），
     * 所以这里不能再单独加 systemPromptChars —— prompts 与 completion 的边界是
     * 「送进去的」与「生成出来的」，不是「系统提示」与「其余」。
     *
     * @param actual           流里采集到的真实用量；可能为 null
     * @param promptChars      本轮送入模型的字符数
     * @param systemPromptChars 系统提示字符数（仅用于日志排查，不参与折算）
     * @param replyChars       模型生成的字符数
     * @return 供事件下发与落库使用的用量
     */
    private StreamUsage resolveUsage(StreamUsage actual, int promptChars, int systemPromptChars, int replyChars) {
        if (actual != null) {
            return actual;
        }
        // 模型名拿不到（provider 未上报）时留空，不编造一个假名字
        StreamUsage estimated = StreamUsage.estimated(null, promptChars, replyChars);
        log.debug("上游未返回用量，本轮按字符估算 promptTokens={} completionTokens={}（系统提示 {} 字符）",
                estimated.promptTokens(), estimated.completionTokens(), systemPromptChars);
        return estimated;
    }

    /**
     * 估算某个会话下一次请求的上下文字符数（仪表盘用）。
     *
     * <p>与流内计算同源，但独立可调：刷新页面后前端拿不到 done 事件，
     * 需要单独查一次才能显示仪表盘。实际拼装规则在 {@link ChatPromptAssembler}。
     */
    public int estimatePromptChars(String conversationId) {
        return promptAssembler.estimatePromptChars(conversationId);
    }

    /** 仪表盘口径：估算 token 数（字符 ÷ 1.6） */
    public int estimatePromptTokens(String conversationId) {
        return ContextBudgetService.toTokens(estimatePromptChars(conversationId));
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

    /** 检索命中 → citations 事件共用 DTO（编号从 1 开始） */
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
     * 发送一个 SSE 事件。
     *
     * <p>发送失败（客户端已断开 / emitter 已结束）只记日志，不向上抛 ——
     * 否则会污染全局异常处理，把「客户端走了」误报成服务端错误。
     *
     * @return {@code false} 表示连接已不可用。调用方据此终止上游订阅：
     *         早期这里只打 debug 就返回，于是客户端关掉页面后模型仍继续生成，
     *         每个 delta 都往死 socket 写一次 —— 白烧额度且日志里几乎看不出来
     */
    private boolean send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE 事件发送失败，客户端可能已断开：event={}", eventName, ex);
            return false;
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
