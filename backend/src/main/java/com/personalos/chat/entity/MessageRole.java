package com.personalos.chat.entity;

/**
 * 消息角色。
 *
 * <p>与数据库 {@code chat_message.message_type} 的取值一一对应。
 * {@code TOOL} 由阶段 2（工具调用）引入，本阶段不产生该角色，
 * 但数据库 CHECK 约束已为其预留取值，届时无需新增迁移。
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
