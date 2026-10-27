package com.personalos.common.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 访问控制配置（阶段 15）。
 *
 * <p><b>默认关闭</b>：本项目的定位是本地单用户，默认开认证会把「clone 下来就能跑」
 * 变成「先配密码」，而且没有为它准备任何向导。
 *
 * <p>默认关闭与「安全默认值」并不矛盾，因为两种默认的风险不对称：
 * 默认关闭的风险是「本机端口暴露」，用户自己看得见；
 * 默认开启的风险是「用户为了跑起来随手设个 123456，然后忘了自己开过认证」—— 这个看不见。
 *
 * <p>变量：
 * <pre>
 * AUTH_ENABLED=true            # 开启后所有 /api/v1/** 需要登录
 * AUTH_USERNAME=admin          # 默认 admin
 * AUTH_PASSWORD=...            # 开启时必填，否则启动失败
 * AUTH_MAX_FAILURES=5          # 连续失败多少次后锁定
 * AUTH_LOCKOUT=60s             # 锁定持续多久
 * </pre>
 *
 * @param enabled     是否启用认证
 * @param username    用户名
 * @param password    明文密码（启动时编码为 BCrypt，之后内存只留哈希）
 * @param maxFailures 连续失败多少次后暂时锁定登录（防暴力破解）
 * @param lockout     锁定时长；到期自动解锁，不需要重启
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        boolean enabled,
        String username,
        String password,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("60s") Duration lockout) {

    public static final String DEFAULT_USERNAME = "admin";

    public AuthProperties {
        username = (username == null || username.isBlank()) ? DEFAULT_USERNAME : username.strip();
        if (maxFailures <= 0) {
            // 配成 0 或负数等于「永不锁定」，静默失效比启动失败危险得多
            throw new IllegalArgumentException("app.auth.max-failures 必须为正数，当前为 " + maxFailures);
        }
        if (lockout == null || lockout.isZero() || lockout.isNegative()) {
            throw new IllegalArgumentException("app.auth.lockout 必须为正的时长，当前为 " + lockout);
        }
    }
}
