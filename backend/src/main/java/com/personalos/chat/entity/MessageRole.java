package com.personalos.chat.entity;

/**
 * 消息角色。
 *
 * <p>与数据库 {@code chat_message.message_type} 的取值一一对应。
 * 数据库 CHECK 约束自 V2 起就为 {@code TOOL} 预留了取值，这里补上枚举值
 * 无需迁移（审查 #13）：工具调用往返时 Spring AI 会写
 * {@code ToolResponseMessage}，映射缺失会让「记录工具结果」这一步直接抛异常，
 * 用户消息已落库、工具结果却写不进去，历史出现断裂。
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    /** 工具调用结果（Spring AI 的 ToolResponseMessage），阶段 2 引入 */
    TOOL
}
