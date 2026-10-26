package com.personalos.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 消息视图。
 *
 * @param id          消息主键
 * @param role        角色：USER / ASSISTANT / SYSTEM
 * @param content     文本内容
 * @param sequenceNo  会话内顺序号（排序依据）
 * @param createdAt   创建时间
 * @param citations   知识库引用快照（阶段 19）。仅助手消息可能非空；
 *                    用户消息恒为空列表——字段不省略，前端少一层判空
 * @param attachments 附件元数据（阶段 22）。仅用户消息可能有；
 *                    文件本体经 {@code /chat/uploads/{id}/content} 取，库里只存这份快照
 */
public record ChatMessageDto(Long id,
                             String role,
                             String content,
                             Integer sequenceNo,
                             Instant createdAt,
                             List<ChatCitationDto> citations,
                             List<ChatAttachmentDto> attachments) {

    /** 无引用、无附件的消息（助手消息 / 未开增强也未带附件的用户消息） */
    public static ChatMessageDto plain(Long id, String role, String content,
                                       Integer sequenceNo, Instant createdAt) {
        return new ChatMessageDto(id, role, content, sequenceNo, createdAt, List.of(), List.of());
    }
}
