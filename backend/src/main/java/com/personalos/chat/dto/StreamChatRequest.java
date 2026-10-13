package com.personalos.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 流式对话请求。
 *
 * @param content 本条用户消息内容
 */
public record StreamChatRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 4000, message = "长度不能超过 4000")
        String content) {
}
