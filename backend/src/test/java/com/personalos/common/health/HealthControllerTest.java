package com.personalos.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.config.AppProperties;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HealthController} 单元测试。
 *
 * <p>本测试不启动 Spring 上下文，只验证 Web 层的出参契约；
 * 真实的 HTTP 映射、序列化与数据库连通性由
 * {@code com.personalos.PersonalOsApplicationTests} 的集成测试覆盖。
 */
class HealthControllerTest {

    @Test
    @DisplayName("health(): 返回统一响应结构，data 携带应用名、版本，status=UP")
    void healthShouldReturnUnifiedEnvelope() {
        HealthController controller =
                new HealthController(new HealthService(new AppProperties("personal-ai-os", "0.1.0-SNAPSHOT")));

        ApiResponse<HealthStatusDto> response = controller.health();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.timestamp()).isNotNull();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().status()).isEqualTo("UP");
        assertThat(response.data().application()).isEqualTo("personal-ai-os");
        assertThat(response.data().version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(response.data().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("health(): 原样透传 HealthService 提供的状态")
    void healthShouldDelegateToService() {
        HealthService healthService = mock(HealthService.class);
        HealthStatusDto stub = new HealthStatusDto("DOWN", "personal-ai-os", "9.9.9", Instant.parse("2026-10-14T00:00:00Z"));
        given(healthService.currentStatus()).willReturn(stub);

        ApiResponse<HealthStatusDto> response = new HealthController(healthService).health();

        assertThat(response.data()).isSameAs(stub);
        assertThat(response.code()).isZero();
    }
}
