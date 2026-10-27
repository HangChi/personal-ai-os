package com.personalos.common.auth;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <h2>为什么用「失败计数 + 锁定」而不是把登录塞进限流桶</h2>
 * 限流按路径匹配，绕过的办法是路径编码（{@code /login} 写成 {@code /%6Cogin}）与换连接；
 * 而且本项目那个 60 次/分钟的桶是给「会花钱的模型调用」用的，登录混进去既互相挤占，
 * 又仍然放行 60 次尝试/分钟 —— 对 6 位数字口令来说等于没防。
 * 失败计数看的是**结果**，与请求怎么发无关：连续失败 N 次即锁定一段时间，
 * 锁定期间连 BCrypt 都不跑。
 *
 * <p>代价是「知道用户名的人可以故意打错来锁住你」。对单用户自托管应用这是可接受的
 * 权衡：攻击者本来就能靠断网达到同样效果，而锁定到期会自动解除，不需要重启。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    /** 启动时算好的哈希；认证关闭时为 null */
    private final String passwordHash;

    /** 连续失败次数（成功一次即清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 锁定截止时刻；{@code Instant.EPOCH} 表示未锁定 */
    private volatile Instant lockedUntil = Instant.EPOCH;

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
     * @throws BusinessException 已被锁定（{@link ErrorCode#RATE_LIMITED}，HTTP 429）
     */
    public boolean verify(String username, String rawPassword) {
        if (!properties.enabled() || username == null || rawPassword == null) {
            return false;
        }
        assertNotLocked();

        boolean passwordOk = passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
        boolean usernameOk = properties.username().equals(username.strip());
        if (usernameOk && passwordOk) {
            // 成功即清零：锁定只针对「连续」失败，正常用户不会因为久以前的失败被误伤
            consecutiveFailures.set(0);
            lockedUntil = Instant.EPOCH;
            return true;
        }
        registerFailure();
        return false;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public String username() {
        return properties.username();
    }

    /** 已锁定时抛 429，并告知还需等待多久 —— 比继续回「用户名或密码错误」更诚实 */
    private void assertNotLocked() {
        Instant now = Instant.now();
        if (!lockedUntil.isAfter(now)) {
            return;
        }
        long seconds = Math.max(1, Duration.between(now, lockedUntil).toSeconds());
        log.warn("登录已被锁定，剩余 {} 秒", seconds);
        throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁，请 " + seconds + " 秒后再试");
    }

    /** 记一次失败；达到阈值即锁定并清零计数（下一轮从 0 开始数） */
    private void registerFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures < properties.maxFailures()) {
            return;
        }
        lockedUntil = Instant.now().plus(properties.lockout());
        consecutiveFailures.set(0);
        log.warn("登录连续失败 {} 次，已锁定 {}，用户名={}",
                failures, properties.lockout(), properties.username());
    }
}
