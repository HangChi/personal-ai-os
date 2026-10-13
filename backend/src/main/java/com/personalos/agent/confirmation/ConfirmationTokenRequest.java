package com.personalos.agent.confirmation;

import jakarta.validation.constraints.NotBlank;

/**
 * 确认 / 取消请求体。
 *
 * @param token 一次性确认令牌
 */
public record ConfirmationTokenRequest(@NotBlank(message = "确认令牌不能为空") String token) {
}
