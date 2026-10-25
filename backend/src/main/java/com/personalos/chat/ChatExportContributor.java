package com.personalos.chat;

import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.service.ChatMessageService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.export.ExportContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 对话历史导出（键 {@code sessions}）。
 *
 * <p>消息**嵌套在会话内**而不是平铺成两个顶层数组：对话的价值在「谁在什么时候说了什么」，
 * 拆成两张表后使用方还要自己按 conversationId 关联，而导出文件本可以省掉这一步。
 */
@Component
public class ChatExportContributor implements ExportContributor {

    /**
     * 一个会话及其全部消息。
     *
     * @param session  会话元数据
     * @param messages 消息，按对话顺序正序
     */
    public record SessionExport(ChatSessionDto session, List<ChatMessageDto> messages) {
    }

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;

    public ChatExportContributor(ChatSessionService sessionService, ChatMessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    @Override
    public String key() {
        return "sessions";
    }

    @Override
    public List<?> snapshot() {
        return sessionService.list().stream()
                .map(session -> new SessionExport(session, messageService.list(session.conversationId())))
                .toList();
    }
}
