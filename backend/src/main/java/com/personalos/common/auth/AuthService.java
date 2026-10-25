package com.personalos.common.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 单用户凭据校验。
 *
 * <p><b>为什么启动时就把密码编码成 BCrypt</b>：内存里长期持有明文没有额外好处，
 * 而 {@link PasswordEncoder#matches} 需要哈希。编码一次、之后只比对哈希。
 *
 * <p>密码来自环境变量（{@code AUTH_PASSWORD}），不进数据库、不进日志。
 * 单用户场景不建 {@code user} 表：建表就要处理「初始密码怎么来」「改密码怎么走」，
 * 而这两件事在自托管场景下有更简单的答案（改 env 重启）。
 */
@Service
public class AuthService {

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    /** 启动时算好的哈希；认证关闭时为 null */
    private final String passwordHash;

    public AuthService(AuthProperties properties, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;

        if (properties.enabled() && (properties.password() == null || properties.password().isBlank())) {
            // 刻意让应用起不来：比「起来了但谁都能进」安全得多 ——
            // 后者用户会以为自己开了认证
            throw new IllegalStateException(
                    "已开启访问控制（AUTH_ENABLED=true），但未设置 AUTH_PASSWORD。"
                            + "请配置密码后重启，或将 AUTH_ENABLED 设为 false。");
        }
        this.passwordHash = properties.enabled() ? passwordEncoder.encode(properties.password()) : null;
    }

    /**
     * 校验凭据。
     *
     * <p>密码比对**总是执行**（即使用户名不匹配），把「用户名错」与「密码错」的耗时差
     * 压到 BCrypt 自身的噪声以内，避免通过响应时间枚举用户名。
     *
     * @return 凭据是否有效；认证关闭时恒为 false（关闭状态下没有「登录」这回事）
     */
    public boolean verify(String username, String rawPassword) {
        if (!properties.enabled() || username == null || rawPassword == null) {
            return false;
        }
        boolean passwordOk = passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
        boolean usernameOk = properties.username().equals(username.strip());
        return usernameOk && passwordOk;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public String username() {
        return properties.username();
    }
}
