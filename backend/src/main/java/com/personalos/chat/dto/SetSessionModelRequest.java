package com.personalos.chat.dto;

/**
 * 设置会话模型的请求体（V23）。
 *
 * <p>{@code model} 为 {@code null} / 空白表示**恢复默认**（沿用当前生效配置的
 * 默认模型），而不是「模型名留空」。两者必须区分：前者会让会话在用户换配置时
 * 自动跟着走，后者会让它卡在一个空模型上。
 *
 * <p>不校验值是否存在于配置的模型清单里：清单是方便挑选的收藏夹，
 * 不是白名单（详见 {@code ChatSessionService#setModel}）。
 *
 * @param model 模型名，如 {@code qwen-max}；null 表示沿用默认
 */
public record SetSessionModelRequest(String model) {
}
