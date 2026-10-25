package com.personalos.chat.dto;

/**
 * 创建会话请求。
 *
 * <p>标题可省略；省略或全空白时服务端以「新会话」为初值，
 * 并在写入首条用户消息时替换为消息前 30 字。
 *
 * <h2>为什么这里没有 {@code @Size}</h2>
 * 与 {@link RenameSessionRequest} 同一理由：注解校验原始长度，业务要求校验归一后长度。
 * 长度上限统一由 {@code Texts.TITLE_MAX_LENGTH} 在 Service 层判定，
 * 避免同一字段出现两种口径（{@code specs/007-chat-session-rename/research.md} R-02 / R-08）。
 *
 * @param title 会话标题，可省略
 */
public record CreateSessionRequest(String title) {
}
