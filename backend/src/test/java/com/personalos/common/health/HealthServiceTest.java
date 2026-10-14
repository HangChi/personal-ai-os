package com.personalos.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link HealthService} 单元测试：依赖探测与状态合成。
 *
 * <p>覆盖三条路径：数据库可用 / 数据库挂 / 模型未配置。
 * 关键断言是「模型未配置不拖垮整体状态」——可选依赖降级而非失败。
 */
class HealthServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AiChatClientProvider aiProvider = mock(AiChatClientProvider.class);

    private HealthService service() {
        return new HealthService(new AppProperties("personal-ai-os", "0.1.0-SNAPSHOT"),
                jdbcTemplate, aiProvider);
    }

    @Test
    @DisplayName("数据库可用 + 模型已配置：整体 UP，分量 database=UP / ai=CONFIGURED")
    void shouldBeUpWhenAllDependenciesReady() {
        given(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).willReturn(1);
        given(aiProvider.isConfigured()).willReturn(true);

        HealthStatusDto status = service().currentStatus();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.components())
                .containsEntry("database", "UP")
                .containsEntry("ai", "CONFIGURED");
    }

    @Test
    @DisplayName("数据库不可用：整体 DOWN（关键依赖），模型状态照常报告")
    void shouldBeDownWhenDatabaseUnreachable() {
        given(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .willThrow(mock(DataAccessException.class));
        given(aiProvider.isConfigured()).willReturn(true);

        HealthStatusDto status = service().currentStatus();

        assertThat(status.status()).isEqualTo("DOWN");
        assertThat(status.components())
                .containsEntry("database", "DOWN")
                .containsEntry("ai", "CONFIGURED");
    }

    @Test
    @DisplayName("模型未配置：整体仍 UP（可选依赖降级），ai=NOT_CONFIGURED")
    void shouldStayUpWhenModelNotConfigured() {
        given(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).willReturn(1);
        given(aiProvider.isConfigured()).willReturn(false);

        HealthStatusDto status = service().currentStatus();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.components()).containsEntry("ai", "NOT_CONFIGURED");
    }
}
