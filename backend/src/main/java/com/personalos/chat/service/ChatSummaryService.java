package com.personalos.chat.service;

import com.personalos.common.ai.ContextProperties;
import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.memory.JpaChatMemory;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.ai.AiChatClientProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会话滚动摘要（阶段 22）。
 *
 * <h2>解决什么问题</h2>
 * 上下文窗口只带最近若干条消息，更早的对话对模型**彻底不可见**。
 * 用户问「我们上周聊的那个方案」时，助手只能答「不知道」——
 * 而那些内容其实还完整躺在 {@code chat_message} 里。
 *
 * <h2>两个刻意的取舍</h2>
 * <ol>
 *   <li><b>只在轮次结束后异步生成</b>，绝不占用当前这一轮的延迟。
 *       代价是摘要总比对话「晚一轮」，可接受 —— 它本来只服务于下一轮。</li>
 *   <li><b>摘要只增不减地向前覆盖</b>（{@code summary_upto_seq} 单调递增）。
 *       异步任务可能乱序完成，允许回退就会把已压缩的内容重新变回「未摘要」。</li>
 * </ol>
 *
 * <h2>失败策略</h2>
 * 全程静默：模型不可用、返回空、超时 —— 一律保留旧摘要并记 debug 日志。
 * 摘要是**增强信息**，它失败不该让用户看到任何错误（对话本身是好的）。
 *
 * <h2>为什么拆成三段（2026-10-27）</h2>
 * 早期实现把「读快照 + 调模型 + 写摘要」全放在一个 {@code TransactionTemplate} 里。
 * 一次摘要的模型调用是一次完整 HTTP，正常 2~5 秒、慢起来几十秒，而那些时间里
 * **一直握着一条数据库连接**。Hikari 池只有 10 条、连接超时 5 秒 ——
 * 几个慢摘要就能把池占满，正常请求（读消息、校验会话）5 秒后失败，
 * 表现是「聊着聊着整个应用卡死」，日志里却只有一行 debug。
 *
 * <p>拆开后：事务 A 只读快照（毫秒级）→ **事务外**调模型 → 事务 B 校验后写入。
 * 模型再慢也只占一个后台线程，不再占连接。
 */
@Service
public class ChatSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryService.class);

    /** 摘要块的标题。格式即契约：注入位置与措辞变化会影响模型对「这是历史概括」的判断 */
    private static final String BLOCK_HEADER = "## 早前对话摘要\n\n";

    private static final String SUMMARY_SYSTEM = """
            你在为一段对话维护滚动摘要。要求：
            - 只保留对后续对话有用的事实：结论、决定、待办、具体数值、用户明确表达的偏好
            - 丢掉寒暄、重复与过程性描述
            - 用第三人称陈述，不使用「我」「你」
            - 不超过 400 字，只输出摘要正文，不要任何前缀或解释
            """;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final JpaChatMemory chatMemory;
    private final AiChatClientProvider aiProvider;
    private final ContextProperties properties;
    private final ExecutorService executor;
    /** 只读快照事务：毫秒级，读完即结束 */
    private final TransactionTemplate readOnlyTemplate;
    /** 写摘要事务：模型调用之后才开，只做一次写入 */
    private final TransactionTemplate writeTemplate;

    public ChatSummaryService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              JpaChatMemory chatMemory,
                              AiChatClientProvider aiProvider,
                              ContextProperties properties,
                              @Qualifier("contextTaskExecutor") ExecutorService executor,
                              PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.chatMemory = chatMemory;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.executor = executor;

        // 没有用 @Transactional：refresh 由异步线程调用，走不到 Spring 代理，
        // 注解会静默失效。显式模板把「事务边界」写成看得见的东西。
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
        this.writeTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 渲染注入系统提示的摘要块。
     *
     * @param conversationId 会话标识
     * @return 可直接拼进系统提示的文本；无摘要或功能关闭时返回空串
     */
    public String renderForConversation(String conversationId) {
        if (!properties.summary().enabled()) {
            return "";
        }
        return sessionRepository.findById(conversationId)
                .map(ChatSessionEntity::getContextSummary)
                .filter(summary -> summary != null && !summary.isBlank())
                .map(summary -> BLOCK_HEADER + summary)
                .orElse("");
    }

    /**
     * 提交一次摘要刷新（异步、尽力而为）。
     *
     * @param conversationId 会话标识
     */
    public void refreshAsync(String conversationId) {
        if (!properties.summary().enabled()) {
            return;
        }
        try {
            executor.execute(() -> refresh(conversationId));
        } catch (RejectedExecutionException ex) {
            // 队列满：丢掉这一轮摘要即可，下一轮还会再来
            log.debug("摘要任务被丢弃（队列已满） conversationId={}", conversationId);
        }
    }

    /**
     * 同步刷新一次摘要。可见于测试，也便于运维手动触发。
     *
     * <p>三段式：只读快照 → **事务外**调模型 → 校验后写入。任何一步失败都保留旧摘要。
     */
    public void refresh(String conversationId) {
        refresh(conversationId, null);
    }

    /** Before sending, summarize a bounded oldest-first batch instead of waiting another turn. */
    public void refreshBeforeRequest(String conversationId, int boundary) {
        if (boundary <= 0) return;
        // Bounded latency and work: subsequent turns continue where the last completed batch ended.
        for (int i = 0; i < 2; i++) if (!refresh(conversationId, boundary)) break;
    }

    private boolean refresh(String conversationId, Integer requestedBoundary) {
        if (!properties.summary().enabled()) return false;
        try {
            SummarySnapshot snapshot = readOnlyTemplate.execute(status -> loadSnapshot(conversationId, requestedBoundary));
            if (snapshot == null) {
                return false; // 窗口没推进、新增消息不足、或会话已删除
            }

            // 事务外：一次完整 HTTP，最慢的一步。绝不能占着数据库连接做这件事
            String summary = summarize(snapshot.existingSummary(), snapshot.transcript());
            if (summary == null || summary.isBlank()) return false;

            String finalSummary = truncate(summary.trim(), properties.summary().maxSummaryChars());
            writeTemplate.executeWithoutResult(status -> applySnapshot(snapshot, finalSummary));
            return true;
        } catch (RuntimeException ex) {
            // 摘要失败不能影响任何用户可见行为
            log.debug("摘要刷新失败，保留旧摘要 conversationId={}", conversationId, ex);
            return false;
        }
    }

    /**
     * 第一段：读快照（只读事务）。
     *
     * @return 没有可摘要的内容时返回 {@code null}
     */
    private SummarySnapshot loadSnapshot(String conversationId, Integer requestedBoundary) {
        ChatSessionEntity session = sessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return null;
        }
        // 窗口起点 - 1 = 最后一条已经滑出窗口的消息
        int boundary = requestedBoundary == null ? chatMemory.contextStartSequence(conversationId) - 1 : requestedBoundary;
        if (boundary <= session.getSummaryUptoSeq()) {
            return null; // 还没有新的消息滑出窗口
        }

        List<ChatMessageEntity> pending = messageRepository.findRangeBySequenceNo(
                conversationId, session.getSummaryUptoSeq() + 1, boundary);
        if (pending.size() < (requestedBoundary == null ? properties.summary().minMessages() : 1)) {
            return null;
        }

        // 把要送模型的内容在这里就拼成纯文本：
        // 一是它只依赖已加载的实体字段（出事务后安全），
        // 二是避免把 JPA 实体带出事务边界（那会引出延迟加载问题）。
        // Only advance the checkpoint across messages actually supplied to the summarizer.
        // The previous newest-only excerpt could mark unseen older messages as summarized.
        List<ChatMessageEntity> batch = new ArrayList<>();
        int chars = 0;
        for (ChatMessageEntity message : pending) {
            int size = (message.getContent() == null ? 0 : message.getContent().length()) + 16;
            if (!batch.isEmpty() && chars + size > properties.summary().maxExcerptChars()) break;
            batch.add(message);
            chars += size;
        }
        if (batch.isEmpty()) return null;
        String transcript = buildTranscript(batch, properties.summary().maxExcerptChars());
        return new SummarySnapshot(conversationId, batch.get(batch.size() - 1).getSequenceNo(), session.getSummaryUptoSeq(),
                session.getContextSummary(), transcript);
    }

    /**
     * 第三段：写入（独立事务）。
     *
     * <p><b>必须重新校验 {@code summaryUptoSeq} 没变</b>：模型调用期间可能已有另一次
     * 摘要跑完并写入了更新的边界。此时本次结果基于过期的输入，直接丢弃 ——
     * 否则会把「已经压缩过的旧内容」重新写回去，或者覆盖掉更新的摘要。
     */
    private void applySnapshot(SummarySnapshot snapshot, String summary) {
        ChatSessionEntity session = sessionRepository.findById(snapshot.conversationId()).orElse(null);
        if (session == null) {
            return;
        }
        if (session.getSummaryUptoSeq() != snapshot.previousUptoSeq()) {
            log.debug("摘要输入已过期，丢弃本次结果 conversationId={} 期望seq={} 实际seq={}",
                    snapshot.conversationId(), snapshot.previousUptoSeq(), session.getSummaryUptoSeq());
            return;
        }
        session.applySummary(summary, snapshot.boundary());
        sessionRepository.save(session);
        log.info("会话摘要已更新 conversationId={} 覆盖到 seq={} 长度={}",
                snapshot.conversationId(), snapshot.boundary(), summary.length());
    }

    /**
     * 一次摘要所需的一切（全部是值，不含实体）。
     *
     * @param conversationId  会话标识
     * @param boundary        本次要覆盖到的边界（窗口起点 - 1）
     * @param previousUptoSeq 读取时的已覆盖边界，写入前用它做乐观校验
     * @param existingSummary 已有摘要（可能为空）
     * @param transcript      已拼好的「角色：内容」纯文本
     */
    /**
     * 调模型生成新摘要；任何异常或超时都返回 null（由调用方保留旧值）。
     *
     * <h2>为什么在虚拟线程里等</h2>
     * Spring AI 的 {@code .call()} 没有单次调用超时（HTTP 超时是全局的，不能为摘要单独收紧 ——
     * 那会连带掐断本该长跑的流式对话）。而模型卡住时不能无限占着一个平台线程：
     * 这个执行器只有 1~2 个线程，两个卡住的摘要就能让后续所有摘要排队。
     *
     * <p>放到虚拟线程里带超时等待，超时就放弃本次结果。被放弃的那次 HTTP 调用
     * 会在后台自然结束，虚拟线程的代价可以忽略 —— 比钉住平台线程划算得多。
     */
    private String summarize(String existingSummary, String transcript) {
        StringBuilder prompt = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            prompt.append("已有摘要（需要与新增对话合并）：\n").append(existingSummary).append("\n\n");
        }
        prompt.append("新增对话：\n").append(transcript);
        String promptText = prompt.toString();

        FutureTask<String> task = new FutureTask<>(() -> aiProvider.requirePlainChatClient()
                .prompt()
                .system(SUMMARY_SYSTEM)
                .user(promptText)
                .call()
                .content());
        Thread.ofVirtual().name("summary-call").start(task);

        Duration timeout = properties.summary().timeout();
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            log.debug("摘要模型调用超时（{}），保留旧摘要", timeout);
            return null;
        } catch (ExecutionException ex) {
            log.debug("摘要模型调用失败", ex.getCause());
            return null;
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 把消息拼成「角色：内容」的纯文本，并**从最新往前**截断到字符上限。
     *
     * <p>从新往前截断：摘要最需要的是最近发生的事，砍掉最早的几句话损失最小。
     */
    static String buildTranscript(List<ChatMessageEntity> messages, int maxChars) {
        List<String> picked = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageEntity message = messages.get(i);
            String line = roleLabel(message.getMessageType()) + "：" + message.getContent();
            if (!picked.isEmpty() && used + line.length() + 1 > maxChars) {
                break;
            }
            if (picked.isEmpty() && line.length() > maxChars) {
                String marker = "\n[该条历史过长，仅保留首尾供摘要]\n";
                int available = maxChars - marker.length();
                line = available > 0 ? line.substring(0, available / 2) + marker
                        + line.substring(line.length() - (available - available / 2)) : line.substring(0, maxChars);
            }
            picked.add(line);
            used += line.length() + 1;
        }
        Collections.reverse(picked);
        return String.join("\n", picked);
    }

    private static String roleLabel(MessageRole role) {
        return switch (role) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            case TOOL -> "工具";
        };
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
