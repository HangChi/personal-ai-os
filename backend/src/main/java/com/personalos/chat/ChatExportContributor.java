package com.personalos.chat;

import com.personalos.chat.service.ChatMessageService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.export.ExportContributor;
import com.personalos.export.ExportWriter;
import tools.jackson.core.JsonGenerator;
import org.springframework.stereotype.Component;

/**
 * 对话历史导出（键 {@code sessions}）。
 *
 * <p>消息**嵌套在会话内**而不是平铺成两个顶层数组：对话的价值在「谁在什么时候说了什么」，
 * 拆成两张表后使用方还要自己按 conversationId 关联，而导出文件本可以省掉这一步。
 */
@Component
public class ChatExportContributor implements ExportContributor {

    private final ExportWriter writer;

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;

    public ChatExportContributor(ChatSessionService sessionService, ChatMessageService messageService, ExportWriter writer) {
        this.writer = writer;
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    @Override
    public String key() {
        return "sessions";
    }

    @Override
    public long writeTo(JsonGenerator json) {
        return writer.array(json, page -> sessionService.exportBatch(page,
                ExportWriter.BATCH_SIZE), (out, session) -> {
            out.writeStartObject();
            out.writePOJOProperty("session", session);
            out.writeName("messages");
            writer.array(out, page -> messageService.exportBatch(session.conversationId(), page,
                    ExportWriter.BATCH_SIZE));
            out.writeEndObject();
        });
    }
}
