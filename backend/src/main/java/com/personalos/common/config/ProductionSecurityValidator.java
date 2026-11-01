package com.personalos.common.config;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 生产 profile 的 fail-closed 守卫；默认本地配置不会触发这些强约束。 */
@Component
public class ProductionSecurityValidator {
    public ProductionSecurityValidator(
            @Value("${app.security.production-mode:false}") boolean productionMode,
            @Value("${app.auth.enabled:false}") boolean authEnabled,
            @Value("${app.security.csrf-enabled:false}") boolean csrfEnabled,
            @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure,
            @Value("${app.auth.password:}") String authPassword,
            @Value("${spring.datasource.password:}") String databasePassword) {
        if (!productionMode) return;
        if (!authEnabled) throw new IllegalStateException("生产配置必须启用 AUTH_ENABLED=true");
        if (!csrfEnabled) throw new IllegalStateException("生产配置必须启用 app.security.csrf-enabled=true");
        if (!cookieSecure) throw new IllegalStateException("生产配置必须启用 SESSION_COOKIE_SECURE=true");
        requireStrong("AUTH_PASSWORD", authPassword);
        if (databasePassword == null || databasePassword.isBlank()
                || databasePassword.equals("personalos") || databasePassword.equals("postgres")) {
            throw new IllegalStateException("生产配置必须设置非默认 POSTGRES_PASSWORD");
        }
    }

    private static void requireStrong(String name, String value) {
        int bytes = value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        if (value == null || value.isBlank() || bytes < 16 || bytes > 72
                || value.equalsIgnoreCase("password") || value.equalsIgnoreCase("changeme")
                || value.equalsIgnoreCase("personalos")) {
            throw new IllegalStateException(name + " 必须为 16-72 字节且不能使用示例密码");
        }
    }
}
