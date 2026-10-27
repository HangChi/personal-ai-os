package com.personalos.chat.service;

import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息查询。
 *
 * <p>消息的**写入**由 {@code JpaChatMemory} 在对话流程中完成（框架 Advisor 驱动），
 * 本服务只负责读取，避免同一份数据出现两条写路径。
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /** 单次分页上限。超过它的请求多半是「想拿全量」，那就该用显式的全量路径。 */
    public static final int MAX_LIMIT = 200;

    private final ChatMessageRepository messageRepository;
    private final ChatSessionService sessionService;
    private final ChatCitationService citationService;
    private final ChatAttachmentService attachmentService;

    public ChatMessageService(ChatMessageRepository messageRepository,
                              ChatSessionService sessionService,
                              ChatCitationService citationService,
                              ChatAttachmentService attachmentService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
        this.citationService = citationService;
        this.attachmentService = attachmentService;
    }

    /**
     * 查询会话全部消息，按顺序正序（FR-003）。
     *
     * <p>会话不存在时抛「资源不存在」，而不是返回空数组 —— 空数组会让调用方
     * 误以为「会话存在但没有消息」。
     *
     * <p>引用快照（阶段 19）按消息 id 一次批量查询后分组装配，不 N+1。
     *
     * <p><b>这是全量路径</b>，供导出与既有调用方使用。界面请走
     * {@link #list(String, Integer, Integer)} —— 一段用了一年的会话可能上千条消息，
     * 一次性返回会让首屏变慢、且前端要一次性渲染全部 DOM。
     */
    /**
     * 回退到某条消息之前：删除 {@code fromSequenceNo} 及其之后的全部消息。
     *
     * <h2>为什么是「删除 + 重发」而不是「原地改」</h2>
     * 重新生成与编辑重发在数据层面是同一件事：**后面的一切都作废**。
     * 原地替换助手消息看着更省事，但会留下「第 5 条被改过、第 6 条还是旧回答引发的」
     * 这种断裂对话 —— 模型下一轮读到的历史是自相矛盾的。
     *
     * <p>回退后由前端重新调用 {@code /stream} 发送（原内容或编辑后的内容），
     * 走的仍是同一条经过验证的链路，不必在 SSE 入口里加分支。
     *
     * @return 实际删除的条数
     */
    @Transactional
    public int rewind(String conversationId, int fromSequenceNo) {
        if (fromSequenceNo <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "fromSequenceNo 必须为正整数");
        }
        // 摘要可能覆盖到被删区间，无法精确裁剪 —— 整段作废，由下一轮重新生成
        sessionService.requireSession(conversationId).invalidateSummaryFrom(fromSequenceNo);
        int deleted = messageRepository.deleteFromSequence(conversationId, fromSequenceNo);
        log.info("回退消息 conversationId={} fromSeq={} 删除 {} 条", conversationId, fromSequenceNo, deleted);
        return deleted;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> list(String conversationId) {
        sessionService.requireSession(conversationId);
        var messages = messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
        return toDtos(messages);
    }

    /**
     * 分页查询会话消息（阶段 21 补：长会话增量加载）。
     *
     * <p>语义刻意与「消息自然顺序」对齐：**取的是最新的一段**，而不是从头开始的第一页。
     * 打开会话时用户关心的是最近的对话，从头分页会让每次进入会话都先看到一年前的内容。
     * 更早的部分由前端以返回结果中最小顺序号作为游标向前翻。
     *
     * @param limit    返回条数；{@code null} 表示不限制（等价于 {@link #list(String)}，
     *                 保留给「不发参数」的既有客户端）
     * @param beforeSeq 只取顺序号**小于**它的消息；{@code null} 表示从最新往前取
     * @return 按顺序号正序的消息列表
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> list(String conversationId, Integer limit, Integer beforeSeq) {
        sessionService.requireSession(conversationId);
        if (limit == null) {
            // 不带分页参数 = 老行为。唯一的无条件分支，保证既有调用方零感知。
            return list(conversationId);
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit 必须在 1~" + MAX_LIMIT + " 之间");
        }
        if (beforeSeq != null && beforeSeq <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "beforeSeq 必须为正整数");
        }

        PageRequest pageRequest = PageRequest.of(0, limit);
        // 仓储只能按顺序号倒序取「最近的 N 条」，反转成正序是调用方的事
        List<ChatMessageEntity> descending = beforeSeq == null
                ? messageRepository.findRecentByConversationId(conversationId, pageRequest)
                : messageRepository.findBeforeSequenceNo(conversationId, beforeSeq, pageRequest);

        List<ChatMessageEntity> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return toDtos(ascending);
    }

    /** 实体 → DTO；引用按消息 id 一次批量查询后分组装配，不 N+1。 */
    private List<ChatMessageDto> toDtos(List<ChatMessageEntity> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ChatCitationDto>> citationsByMessage =
                citationService.findByMessageIds(messages.stream().map(ChatMessageEntity::getId).toList());
        return messages.stream()
                .map(message -> new ChatMessageDto(
                        message.getId(),
                        message.getMessageType().name(),
                        message.getContent(),
                        message.getSequenceNo(),
                        message.getCreatedAt(),
                        citationsByMessage.getOrDefault(message.getId(), List.of()),
                        attachmentService.fromJson(message.getAttachments())))
                .toList();
    }
}
