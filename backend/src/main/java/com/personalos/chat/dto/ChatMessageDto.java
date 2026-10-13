package com.personalos.chat.dto;

import java.time.Instant;

/**
 * 消息视图。
 *
 * @param id         消息主键
 * @param role       角色：USER / ASSISTANT / SYSTEM
 * @param content    文本内容
 * @param sequenceNo 会话内顺序号（排序依据）
 * @param createdAt  创建时间
 */
public record ChatMessageDto(Long id,
                             String role,
                             String content,
                             Integer sequenceNo,
                             Instant createdAt) {
}
