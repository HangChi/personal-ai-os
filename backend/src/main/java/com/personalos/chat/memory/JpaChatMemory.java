package com.personalos.chat.memory;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.ai.AiProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于 JPA 的 {@link ChatMemory} 实现。
 *
 * <h2>为什么不用框架自带的 MessageWindowChatMemory</h2>
 * {@code MessageWindowChatMemory} 的语义是「维护一个窗口」：每次 {@code add} 之后，
 * 它会把**裁剪过的窗口列表**整体写回仓库。如果仓库就是持久化历史，
 * 那么每轮对话都会用窗口覆盖历史 —— 窗口滑出的旧消息被静默删除。
 * 这与「历史不得被裁剪」（FR-007）直接冲突，且症状是「用久了早期对话莫名消失」，极难排查。
 *
 * <p>本实现把两件事彻底解耦：
 * <ul>
 *   <li><b>持久化历史</b>：只追加，永不裁剪（{@link #add}）；</li>
 *   <li><b>送给模型的上下文</b>：只取最近 N 条（{@link #get}，N 来自配置）。</li>
 * </ul>
 */
@Component
public class JpaChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(JpaChatMemory.class);

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final AiProperties aiProperties;
    private final com.personalos.common.ai.ContextBudgetService contextBudget;
    /**
     * 写路径的显式事务模板。
     *
     * <p><b>为什么 add 不用 {@code @Transactional}</b>（2026-10-27 集成测试实锤）：
     * {@code MessageChatMemoryAdvisor.before()} 调的是 {@code ChatMemory} 接口的
     * default 方法 {@code add(String, Message)}，其内部 {@code this.add(String, List)}
     * 是 self-invocation —— 绕过了 Spring 代理，方法上的 {@code @Transactional}
     * **静默失效**。此前没暴露，是因为 {@code saveAll} 自带事务、普通查询无事务也能跑；
     * 但悲观锁查询（{@code FOR UPDATE}）必须在活动事务里执行，一上锁立刻抛
     * {@code No active transaction}。显式 TransactionTemplate 不经过代理，没有这个隐患。
     */
    private final TransactionTemplate writeTransaction;

    public JpaChatMemory(ChatMessageRepository messageRepository,
                         ChatSessionRepository sessionRepository,
                         AiProperties aiProperties,
                         com.personalos.common.ai.ContextBudgetService contextBudget,
                         PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.aiProperties = aiProperties;
        this.contextBudget = contextBudget;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 追加消息。顺序号从当前最大值继续递增，**不删除任何既有记录**。
     *
     * <p>同时刷新会话的最近活动时间（FR-019）。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 整个写路径包在显式事务里（见 writeTransaction 的注释：@Transactional 在这里会失效）
        writeTransaction.executeWithoutResult(status -> {
            // 先锁会话行（审查 #13）：把「同会话的并发写」串行化。
            // 否则 findMaxSequenceNo + saveAll 是 check-then-act——两个并发请求都读到
            // 同一个 max，各自 +1 后撞 UNIQUE(conversation_id, sequence_no)。
            sessionRepository.findByIdForUpdate(conversationId);

            int nextSequence = messageRepository.findMaxSequenceNo(conversationId);
            Instant now = Instant.now();

            List<ChatMessageEntity> entities = new ArrayList<>(messages.size());
            for (Message message : messages) {
                nextSequence++;
                entities.add(new ChatMessageEntity(
                        conversationId,
                        toRole(message.getMessageType()),
                        contentOf(message),
                        nextSequence,
                        now));
            }
            messageRepository.saveAll(entities);

            sessionRepository.findById(conversationId).ifPresent(session -> {
                session.setUpdatedAt(now);
                sessionRepository.save(session);
            });
        });
    }

    /**
     * 取出最近的消息作为模型上下文，按顺序正序返回。
     *
     * <h2>双约束（2026-10-26）</h2>
     * <ol>
     *   <li><b>条数</b>（{@code app.ai.memory.max-messages}，默认 20）：
     *       作为粗筛，避免把长会话整段读进内存；</li>
     *   <li><b>字符预算</b>（{@code app.ai.memory.max-context-chars}，默认 12000）：
     *       真正的窗口上界。从最新往旧累加，超出预算就整体停 ——
     *       不切半条消息：半截对话比缺失更容易让模型误解。</li>
     * </ol>
     *
     * <p>预算耗尽时的取舍是「丢更早的」，因此最新一条**无条件保留**：
     * 即便它自己就超预算（单条上限 4000 字，理论上不该发生），
     * 送一个空上下文比送一条超长消息更糟。
     */
    @Override
    public List<Message> get(String conversationId) {
        return selectWindow(conversationId).stream().map(this::toMessage).toList();
    }

    /**
     * 当前上下文窗口里**最早一条**的 sequence_no；窗口为空时返回 maxSeq + 1。
     *
     * <p>供滚动摘要定位「哪些消息已经滑出窗口」用。刻意复用同一套窗口计算
     * （{@link #selectWindow}）：如果摘要按「maxSeq - maxMessages」另算一遍边界，
     * 一旦字符预算生效让窗口变短，两者就会出现缝隙 —— 那些消息既不在窗口里、
     * 也没被摘要覆盖，等于凭空消失。
     */
    public int contextStartSequence(String conversationId) {
        List<ChatMessageEntity> window = selectWindow(conversationId);
        if (window.isEmpty()) {
            return messageRepository.findMaxSequenceNo(conversationId) + 1;
        }
        return window.get(0).getSequenceNo();
    }

    /**
     * 窗口选择的唯一实现：先按条数粗筛（仓储倒序取），再按字符预算从新到旧裁剪。
     *
     * @return 窗口内的消息实体，**正序**（最早在前）
     */
    private List<ChatMessageEntity> selectWindow(String conversationId) {
        int limit = aiProperties.memory().maxMessages();
        // 字符预算来自「用户选择的 token 预算」折算，而不是配置里的写死阈值 ——
        // 这样仪表盘显示的分母与实际裁剪阈值永远是同一个数。
        //
        // V25 起按**该会话所用模型**的窗口收敛：用户在 1M 模型下选了 300K 预算，
        // 切到 32K 窗口的模型后必须按 32K 裁，否则请求会被端点拒
        // （400 context length exceeded），而界面上还显示「才用了 30%」。
        int charBudget = contextBudget.effectiveCharBudgetFor(resolveSessionModel(conversationId));

        // 仓储按 sequenceNo 倒序取出「最后 N 条」（最新在前）
        List<ChatMessageEntity> recent = messageRepository
                .findRecentByConversationId(conversationId, PageRequest.of(0, limit));

        List<ChatMessageEntity> picked = new ArrayList<>(recent.size());
        int used = 0;
        for (ChatMessageEntity entity : recent) {
            int cost = entity.getContent() == null ? 0 : entity.getContent().length();
            if (!picked.isEmpty() && used + cost > charBudget) {
                break;
            }
            picked.add(entity);
            used += cost;
        }
        // 上面按「新 → 旧」挑，这里转回正序
        Collections.reverse(picked);
        return picked;
    }

    /**
     * 该会话实际使用的模型名；没显式选过时回落到生效配置的默认模型。
     *
     * <p>查会话表是一次主键查询，与窗口裁剪同样在「送模型之前」这条路径上 ——
     * 相比一次模型调用的耗时可以忽略，不值得为它加缓存（缓存反而要在
     * 「用户切模型」时失效，容易漏）。
     *
     * <p>读失败不能让聊天挂掉：返回 null，{@link ContextBudgetService#contextWindowTokens(String)}
     * 会回落到内置表或全局默认。宁可窗口估得保守，也不能因为读不到一个可选的
     * 优化项而拒绝服务。
     */
    private String resolveSessionModel(String conversationId) {
        try {
            return sessionRepository.findById(conversationId)
                    .map(entity -> entity.getChatModel() == null || entity.getChatModel().isBlank()
                            ? null
                            : entity.getChatModel())
                    // 会话没选模型 → null 表示「跟随生效配置」，由预算服务回落到默认窗口。
                    // 这里不主动去查配置的默认模型名：那样会引入对 ModelProfileService
                    // 的依赖，而预算服务本来就能用全局默认值兜住这一档。
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("读取会话 {} 的模型失败，窗口按默认处理：{}", conversationId, ex.toString());
            return null;
        }
    }

    /**
     * 清空该会话的全部消息（配合删除会话使用）。
     *
     * <p>同样走显式事务：删除是写操作，必须真实提交；且与 add 一样不依赖
     * 会被 self-invocation 绕过的 {@code @Transactional} 代理。
     */
    @Override
    public void clear(String conversationId) {
        writeTransaction.executeWithoutResult(status -> messageRepository.deleteByConversationId(conversationId));
    }

    private MessageRole toRole(MessageType messageType) {
        return switch (messageType) {
            case USER -> MessageRole.USER;
            case ASSISTANT -> MessageRole.ASSISTANT;
            case SYSTEM -> MessageRole.SYSTEM;
            case TOOL -> MessageRole.TOOL;
            default -> {
                // 未知类型降级为 ASSISTANT + warn：绝不在写路径抛异常。
                // 抛异常的后果是「用户消息已落库、这条写不进去」——历史出现断裂，
                // 而下一轮模型会把那条悬空的提问当事实。降级至少保住上下文连续。
                log.warn("未知消息类型 {}，降级为 ASSISTANT 写入", messageType);
                yield MessageRole.ASSISTANT;
            }
        };
    }

    /**
     * 提取消息的落库文本。
     *
     * <p>{@code ToolResponseMessage} 的 {@code getText()} 恒为 null（它的正文在
     * {@code responses} 里，每个是 {@code id + name + responseData}）。只存 getText()
     * 会把工具结果存成空串——历史里这条等于没写。这里把 responses 拼成可读文本，
     * 至少让「工具返回了什么」在上下文里可追溯。
     */
    private String contentOf(Message message) {
        if (message instanceof ToolResponseMessage toolResponse) {
            return toolResponse.getResponses().stream()
                    .map(response -> response.name() + ": " + response.responseData())
                    .collect(Collectors.joining("\n"));
        }
        return Objects.requireNonNullElse(message.getText(), "");
    }

    private Message toMessage(ChatMessageEntity entity) {
        return switch (entity.getMessageType()) {
            case USER -> new UserMessage(entity.getContent());
            case ASSISTANT -> new AssistantMessage(entity.getContent());
            case SYSTEM -> new SystemMessage(entity.getContent());
            // 工具结果读回时作为助手文本呈现：无法从 content 重建 ToolResponse 的
            // id/name 结构（写库时只留了拼接文本），用文本形式保住「工具返回了什么」即可
            case TOOL -> new AssistantMessage(entity.getContent());
        };
    }
}
