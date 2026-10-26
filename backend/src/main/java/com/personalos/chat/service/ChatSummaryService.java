package com.personalos.chat.service;

import com.personalos.common.ai.ContextProperties;
import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.memory.JpaChatMemory;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.ai.AiChatClientProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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
    private final TransactionTemplate transactionTemplate;

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
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
     * <p>没有用 {@code @Transactional}：它由异步线程调用，走不到 Spring 代理，
     * 注解会静默失效。这里显式用 TransactionTemplate，把「事务边界」写成看得见的东西。
     */
    public void refresh(String conversationId) {
        try {
            transactionTemplate.executeWithoutResult(status -> doRefresh(conversationId));
        } catch (RuntimeException ex) {
            // 摘要失败不能影响任何用户可见行为
            log.debug("摘要刷新失败，保留旧摘要 conversationId={}", conversationId, ex);
        }
    }

    private void doRefresh(String conversationId) {
        ChatSessionEntity session = sessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return;
        }
        // 窗口起点 - 1 = 最后一条已经滑出窗口的消息
        int boundary = chatMemory.contextStartSequence(conversationId) - 1;
        if (boundary <= session.getSummaryUptoSeq()) {
            return; // 还没有新的消息滑出窗口
        }

        List<ChatMessageEntity> pending = messageRepository.findRangeBySequenceNo(
                conversationId, session.getSummaryUptoSeq() + 1, boundary);
        if (pending.size() < properties.summary().minMessages()) {
            return;
        }

        String summary = summarize(session.getContextSummary(), pending);
        if (summary == null || summary.isBlank()) {
            return;
        }
        session.applySummary(truncate(summary.trim(), properties.summary().maxSummaryChars()), boundary);
        sessionRepository.save(session);
        log.info("会话摘要已更新 conversationId={} 覆盖到 seq={} 长度={}",
                conversationId, boundary, session.getContextSummary().length());
    }

    /** 调模型生成新摘要；任何异常返回 null（由调用方保留旧值） */
    private String summarize(String existingSummary, List<ChatMessageEntity> pending) {
        StringBuilder prompt = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            prompt.append("已有摘要（需要与新增对话合并）：\n").append(existingSummary).append("\n\n");
        }
        prompt.append("新增对话：\n").append(buildTranscript(pending, properties.summary().maxExcerptChars()));
        try {
            return aiProvider.requirePlainChatClient()
                    .prompt()
                    .system(SUMMARY_SYSTEM)
                    .user(prompt.toString())
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            log.debug("摘要模型调用失败", ex);
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
        };
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
