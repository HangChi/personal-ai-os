package com.personalos.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 消息视图。
 *
 * @param id         消息主键
 * @param role       角色：USER / ASSISTANT / SYSTEM
 * @param content    文本内容
 * @param sequenceNo 会话内顺序号（排序依据）
 * @param createdAt  创建时间
 * @param citations  知识库引用快照（阶段 19）。仅助手消息可能非空；
 *                   用户消息恒为空列表——字段不省略，前端少一层判空
 */
public record ChatMessageDto(Long id,
                             String role,
                             String content,
                             Integer sequenceNo,
                             Instant createdAt,
                             List<ChatCitationDto> citations) {

    /** 无引用的消息（用户消息 / 未开增强的助手消息） */
    public static ChatMessageDto withoutCitations(Long id, String role, String content,
                                                  Integer sequenceNo, Instant createdAt) {
        return new ChatMessageDto(id, role, content, sequenceNo, createdAt, List.of());
    }
}
