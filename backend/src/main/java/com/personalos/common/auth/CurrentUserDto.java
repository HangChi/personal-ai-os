package com.personalos.common.auth;

/**
 * 当前登录状态。
 *
 * <p>未登录时 {@code username} 为 {@code null} 而 HTTP 状态仍是 200 —— 前端需要
 * 用这个结果判断「要不要显示登录页」，如果把未登录做成 401，前端的 401 拦截器
 * 会把「首次进入应用」当成「会话过期」。
 *
 * @param username    当前用户名；未登录时为 null
 * @param authEnabled 是否启用了访问控制（关闭时前端不显示登录页）
 */
public record CurrentUserDto(String username, boolean authEnabled) {

    public static CurrentUserDto anonymous(boolean authEnabled) {
        return new CurrentUserDto(null, authEnabled);
    }
}
