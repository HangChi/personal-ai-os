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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final AiProperties aiProperties;

    public JpaChatMemory(ChatMessageRepository messageRepository,
                         ChatSessionRepository sessionRepository,
                         AiProperties aiProperties) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.aiProperties = aiProperties;
    }

    /**
     * 追加消息。顺序号从当前最大值继续递增，**不删除任何既有记录**。
     *
     * <p>同时刷新会话的最近活动时间（FR-019）。
     */
    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int nextSequence = messageRepository.findMaxSequenceNo(conversationId);
        Instant now = Instant.now();

        List<ChatMessageEntity> entities = new ArrayList<>(messages.size());
        for (Message message : messages) {
            nextSequence++;
            entities.add(new ChatMessageEntity(
                    conversationId,
                    toRole(message.getMessageType()),
                    // content 为 NOT NULL；工具调用等场景下 getText() 可能为 null
                    Objects.requireNonNullElse(message.getText(), ""),
                    nextSequence,
                    now));
        }
        messageRepository.saveAll(entities);

        sessionRepository.findById(conversationId).ifPresent(session -> {
            session.setUpdatedAt(now);
            sessionRepository.save(session);
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
        int charBudget = aiProperties.memory().maxContextChars();

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
     * 清空该会话的全部消息（配合删除会话使用）。
     */
    @Override
    @Transactional
    public void clear(String conversationId) {
        messageRepository.deleteByConversationId(conversationId);
    }

    private MessageRole toRole(MessageType messageType) {
        return switch (messageType) {
            case USER -> MessageRole.USER;
            case ASSISTANT -> MessageRole.ASSISTANT;
            case SYSTEM -> MessageRole.SYSTEM;
            default -> throw new IllegalStateException(
                    "阶段 1 不支持的消息类型：" + messageType + "（阶段 2 引入工具调用时需同步扩展本映射）");
        };
    }

    private Message toMessage(ChatMessageEntity entity) {
        return switch (entity.getMessageType()) {
            case USER -> new UserMessage(entity.getContent());
            case ASSISTANT -> new AssistantMessage(entity.getContent());
            case SYSTEM -> new SystemMessage(entity.getContent());
        };
    }
}
