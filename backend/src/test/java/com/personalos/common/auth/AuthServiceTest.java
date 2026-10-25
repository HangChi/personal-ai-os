package com.personalos.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@link AuthService} 单元测试。
 *
 * <p>重点是「配置错误时启动失败」与「关闭时不会误放行」这两条 —— 它们都只在
 * 特定配置下出现，集成测试很难覆盖全。
 */
class AuthServiceTest {

    private static AuthService service(boolean enabled, String password) {
        return new AuthService(new AuthProperties(enabled, "admin", password), new BCryptPasswordEncoder());
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
}
