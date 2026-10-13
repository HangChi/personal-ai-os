package com.personalos.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建会话请求。
 *
 * @param title 会话标题，可省略；省略时服务端以「新会话」为初值，
 *              并在写入首条用户消息时替换为消息前 30 字
 */
public record CreateSessionRequest(
        @Size(max = 200, message = "标题长度不能超过 200") String title) {
}
