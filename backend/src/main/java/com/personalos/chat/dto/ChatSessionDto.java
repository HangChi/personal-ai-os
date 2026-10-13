package com.personalos.chat.dto;

import java.time.Instant;

/**
 * 会话视图。
 *
 * @param conversationId 会话标识
 * @param title          标题
 * @param createdAt      创建时间
 * @param updatedAt      最近活动时间
 * @param messageCount   消息条数
 */
public record ChatSessionDto(String conversationId,
                             String title,
                             Instant createdAt,
                             Instant updatedAt,
                             long messageCount) {
}
