package com.personalos.chat.service;

import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.repository.ChatMessageRepository;
import java.util.List;
import java.util.Map;
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
    private final ChatCitationService citationService;

    public ChatMessageService(ChatMessageRepository messageRepository,
                              ChatSessionService sessionService,
                              ChatCitationService citationService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
        this.citationService = citationService;
    }

    /**
     * 查询会话全部消息，按顺序正序（FR-003）。
     *
     * <p>会话不存在时抛「资源不存在」，而不是返回空数组 —— 空数组会让调用方
     * 误以为「会话存在但没有消息」。
     *
     * <p>引用快照（阶段 19）按消息 id 一次批量查询后分组装配，不 N+1。
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> list(String conversationId) {
        sessionService.requireSession(conversationId);
        var messages = messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
        Map<Long, List<ChatCitationDto>> citationsByMessage =
                citationService.findByMessageIds(messages.stream().map(message -> message.getId()).toList());
        return messages.stream()
                .map(message -> new ChatMessageDto(
                        message.getId(),
                        message.getMessageType().name(),
                        message.getContent(),
                        message.getSequenceNo(),
                        message.getCreatedAt(),
                        citationsByMessage.getOrDefault(message.getId(), List.of())))
                .toList();
    }
}
