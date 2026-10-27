package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.chat.dto.UsageStatsDto;
import com.personalos.chat.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link UsageStatsService} 的聚合逻辑测试。
 *
 * <h2>为什么用 mock 而不是集成测试</h2>
 * 这个类真正容易错的地方是**纯计算**：同一太阳日的两行合并、估算占比、
 * 「全估算」的判定、窗口边界。SQL 本身（{@code AT TIME ZONE} 分组）
 * 由集成测试覆盖，两者互为补充 —— 用真实数据库测计算分支要造几十行数据，
 * 读起来全是噪音。
 */
class UsageStatsServiceTest {

    /** 固定到东八区的某个下午，便于断言「今天」 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-27T06:00:00Z");

    private TokenUsageRepository repository;
    private UsageStatsService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TokenUsageRepository.class);
        service = new UsageStatsService(repository, Clock.fixed(NOW, ZONE), ZONE);
    }

    @Test
    @DisplayName("同一天的 ACTUAL 与 ESTIMATED 合并成一行，且分开计入占比")
    void mergesSameDayRows() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-26", "ACTUAL", 1000L, 100L, 2L},
                new Object[]{"2026-10-26", "ESTIMATED", 200L, 20L, 1L}));

        UsageStatsDto stats = service.stats(7);

        assertThat(stats.daily()).hasSize(1);
        UsageStatsDto.DailyUsageDto day = stats.daily().get(0);
        assertThat(day.date()).isEqualTo("2026-10-26");
        assertThat(day.promptTokens()).isEqualTo(1200);
        assertThat(day.completionTokens()).isEqualTo(120);
        assertThat(day.totalTokens()).isEqualTo(1320);
        assertThat(day.turns()).isEqualTo(3);
        // 有真实数据混在里面，这一天不该被标成「纯估算」
        assertThat(day.estimated()).isFalse();
        assertThat(stats.actualTokens()).isEqualTo(1100);
        assertThat(stats.estimatedTokens()).isEqualTo(220);
    }

    @Test
    @DisplayName("整天只有估算数据时标 estimated=true")
    void marksFullyEstimatedDay() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-25", "ESTIMATED", 300L, 30L, 1L}));

        UsageStatsDto stats = service.stats(7);

        assertThat(stats.daily().get(0).estimated()).isTrue();
        assertThat(stats.actualTokens()).isZero();
        assertThat(stats.estimatedTokens()).isEqualTo(330);
    }

    @Test
    @DisplayName("没有记录时 hasData=false（前端据此不画零值图）")
    void emptyWindowReportsNoData() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of());

        UsageStatsDto stats = service.stats(30);

        assertThat(stats.hasData()).isFalse();
        assertThat(stats.daily()).isEmpty();
        assertThat(stats.totalTokens()).isZero();
    }

    @Test
    @DisplayName("天数越界被收敛，并在响应里回报实际生效值")
    void clampsDays() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of());

        assertThat(service.stats(9999).days()).isEqualTo(UsageStatsService.MAX_DAYS);
        assertThat(service.stats(0).days()).isEqualTo(1);
        assertThat(service.stats(-5).days()).isEqualTo(1);
        assertThat(service.stats(null).days()).isEqualTo(UsageStatsService.DEFAULT_DAYS);
    }

    @Test
    @DisplayName("窗口终点取「明天 00:00」而不是当前时刻")
    void windowEndIsTomorrowMidnight() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of());

        service.stats(1);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(repository).aggregateByDay(from.capture(), to.capture(), anyString());
        // 东八区的「今天」是 10-27，起点 00:00 = 10-26T16:00Z，终点（明天 00:00）= 10-27T16:00Z
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-26T16:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-27T16:00:00Z"));
    }

    @Test
    @DisplayName("分组时区与窗口时区是同一个（避免跨日错位）")
    void usesSameZoneForGroupingAndWindow() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of());

        service.stats(7);

        verify(repository).aggregateByDay(any(), any(), org.mockito.ArgumentMatchers.eq("Asia/Shanghai"));
    }

    @Test
    @DisplayName("聚合列是 BigInteger 时也能读出来（JDBC 常见类型差异）")
    void toleratesBigIntegerColumns() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-27", "ACTUAL", new java.math.BigInteger("500"),
                        new java.math.BigInteger("50"), new java.math.BigInteger("1")}));

        UsageStatsDto stats = service.stats(1);

        assertThat(stats.totalPrompt()).isEqualTo(500);
        assertThat(stats.turns()).isEqualTo(1);
    }

    @Test
    @DisplayName("多天按接口返回顺序聚合，不做重排序")
    void keepsRepositoryOrder() {
        when(repository.aggregateByDay(any(), any(), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-25", "ACTUAL", 10L, 1L, 1L},
                new Object[]{"2026-10-26", "ACTUAL", 20L, 2L, 1L}));

        UsageStatsDto stats = service.stats(7);

        assertThat(stats.daily()).extracting(UsageStatsDto.DailyUsageDto::date)
                .containsExactly("2026-10-25", "2026-10-26");
        assertThat(stats.totalTokens()).isEqualTo(33);
    }
}
