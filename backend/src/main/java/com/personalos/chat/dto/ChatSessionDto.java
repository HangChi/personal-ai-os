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
 * @param chatModel      该会话选定的对话模型名（V23）；null 表示沿用生效配置的默认模型
 */
public record ChatSessionDto(String conversationId,
                             String title,
                             Instant createdAt,
                             Instant updatedAt,
                             long messageCount,
                             String chatModel) {

    /**
     * 兼容构造器：不带模型的旧调用点。
     *
     * <p>保留它是因为「会话没有专属模型」是常态，让每个构造点都写一个
     * {@code null} 只是噪音。新增字段时优先加兼容构造器，而不是批量改调用点。
     */
    public ChatSessionDto(String conversationId, String title, Instant createdAt,
                          Instant updatedAt, long messageCount) {
        this(conversationId, title, createdAt, updatedAt, messageCount, null);
    }
}
