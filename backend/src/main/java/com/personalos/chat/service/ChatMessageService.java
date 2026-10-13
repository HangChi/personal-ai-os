package com.personalos.chat.service;

import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.repository.ChatMessageRepository;
import java.util.List;
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

    private final ChatMessageRepository messageRepository;
    private final ChatSessionService sessionService;

    public ChatMessageService(ChatMessageRepository messageRepository, ChatSessionService sessionService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    /**
     * 查询会话全部消息，按顺序正序（FR-003）。
     *
     * <p>会话不存在时抛「资源不存在」，而不是返回空数组 —— 空数组会让调用方
     * 误以为「会话存在但没有消息」。
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> list(String conversationId) {
        sessionService.requireSession(conversationId);
        return messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId).stream()
                .map(message -> new ChatMessageDto(
                        message.getId(),
                        message.getMessageType().name(),
                        message.getContent(),
                        message.getSequenceNo(),
                        message.getCreatedAt()))
                .toList();
    }
}
