package com.personalos.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@link AuthService} 单元测试。
 *
 * <p>重点是「配置错误时启动失败」「关闭时不会误放行」「连续失败会锁定」这三条 ——
 * 它们都只在特定配置或特定调用序列下出现，集成测试很难覆盖全。
 */
class AuthServiceTest {

    /**
     * 常规用例用很高的失败阈值：这些用例关心的是凭据判定，
     * 不应该因为多调了几次 verify 就撞上锁定（锁定有专门的用例）。
     */
    private static AuthService service(boolean enabled, String password) {
        return service(enabled, password, 100, Duration.ofSeconds(60));
    }

    private static AuthService service(boolean enabled, String password, int maxFailures, Duration lockout) {
        return new AuthService(new AuthProperties(enabled, "admin", password, maxFailures, lockout),
                new BCryptPasswordEncoder());
    }

    @Test
    @DisplayName("开启认证但没配密码：构造即失败，并指出缺哪个变量")
    void shouldFailFastWhenPasswordMissing() {
        assertThatThrownBy(() -> service(true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PASSWORD");

        assertThatThrownBy(() -> service(true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PASSWORD");
    }

    @Test
    @DisplayName("凭据正确时通过")
    void shouldAcceptCorrectCredentials() {
        AuthService auth = service(true, "test-secret");

        assertThat(auth.verify("admin", "test-secret")).isTrue();
    }

    @Test
    @DisplayName("密码错、用户名错、大小写不同：都不通过")
    void shouldRejectWrongCredentials() {
        AuthService auth = service(true, "test-secret");

        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("root", "test-secret")).isFalse();
        assertThat(auth.verify("Admin", "test-secret")).isFalse();
        assertThat(auth.verify("admin", "")).isFalse();
        assertThat(auth.verify(null, "test-secret")).isFalse();
        assertThat(auth.verify("admin", null)).isFalse();
    }

    @Test
    @DisplayName("用户名两侧空白被忽略（从终端复制粘贴时常见）")
    void shouldTrimUsername() {
        AuthService auth = service(true, "test-secret");

        assertThat(auth.verify("  admin  ", "test-secret")).isTrue();
    }

    @Test
    @DisplayName("认证关闭时 verify 恒为 false —— 关闭状态下不存在「登录成功」")
    void shouldNeverVerifyWhenDisabled() {
        AuthService auth = service(false, "test-secret");

        assertThat(auth.enabled()).isFalse();
        assertThat(auth.verify("admin", "test-secret")).isFalse();
    }

    @Test
    @DisplayName("密码不会被明文保留在可访问字段里")
    void shouldNotExposeRawPassword() {
        AuthService auth = service(true, "test-secret");

        // 通过公开方法拿不到明文（这里只能验证接口层面没有 getPassword 之类）
        assertThat(auth.username()).isEqualTo("admin");
        assertThat(auth.getClass().getMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("password"));
    }

    @Test
    @DisplayName("连续失败达到阈值即锁定：锁定期内即使密码正确也拒绝（而不是回「密码错误」）")
    void shouldLockAfterConsecutiveFailures() {
        AuthService auth = service(true, "test-secret", 3, Duration.ofSeconds(60));

        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "wrong")).isFalse(); // 第 3 次触发锁定

        // 关键断言：正确密码也被拒。若仍回 false，攻击者可以继续无限尝试
        assertThatThrownBy(() -> auth.verify("admin", "test-secret"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过于频繁");
    }

    @Test
    @DisplayName("成功一次即清零失败计数：偶发手滑不会累积成锁定")
    void shouldResetFailureCounterOnSuccess() {
        AuthService auth = service(true, "test-secret", 3, Duration.ofSeconds(60));

        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "test-secret")).isTrue(); // 清零

        // 再来两次仍不该锁
        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "wrong")).isFalse();
        assertThat(auth.verify("admin", "test-secret")).isTrue();
    }

    @Test
    @DisplayName("锁定期满自动解锁，不需要重启")
    void shouldUnlockAfterLockoutExpires() throws InterruptedException {
        AuthService auth = service(true, "test-secret", 1, Duration.ofMillis(50));

        assertThat(auth.verify("admin", "wrong")).isFalse(); // 阈值 1，立刻锁定
        assertThatThrownBy(() -> auth.verify("admin", "test-secret"))
                .isInstanceOf(BusinessException.class);

        Thread.sleep(120);

        assertThat(auth.verify("admin", "test-secret")).isTrue();
    }

    @Test
    @DisplayName("锁定参数配成 0 或负值：启动即失败，而不是静默永不锁定")
    void shouldRejectInvalidLockoutConfig() {
        assertThatThrownBy(() -> new AuthProperties(true, "admin", "x", 0, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-failures");

        assertThatThrownBy(() -> new AuthProperties(true, "admin", "x", 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockout");
    }
}
