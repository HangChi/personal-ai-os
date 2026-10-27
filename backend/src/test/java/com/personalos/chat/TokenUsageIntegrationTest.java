package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.entity.TokenUsageEntity;
import com.personalos.chat.entity.TokenUsageSource;
import com.personalos.chat.repository.TokenUsageRepository;
import com.personalos.chat.service.TokenUsageRecorder;
import com.personalos.chat.support.StreamUsage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 用量统计的端到端约束（清单第 7 项）。
 *
 * <p>验证三件必须同时成立的事：
 * <ol>
 *   <li>V15 的表真的建出来了（{@code ddl-auto=validate} 只能保证实体字段对得上）；</li>
 *   <li>写入能落库，且 {@code source} 如实标注；</li>
 *   <li>按日聚合在**本地时区**分组 —— 这是最容易错、也最难在手工测试中发现的一条。</li>
 * </ol>
 *
 * <h2>安全约束（血的教训）</h2>
 * 本类带 {@code @SpringBootTest} 与清表逻辑，必须使用 Testcontainers，
 * **且**在 {@code @BeforeEach} 里断言连的是容器库。缺少任一条件时，
 * 测试会连到开发者本机的 {@code localhost:55432} 并把真实数据删光 ——
 * 这在 2026-10-27 真实发生过一次。
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        // 关掉限流：令牌桶是进程级单例，跨测试上下文共享计数（见 src/test/resources/application.properties）
        "app.rate-limit.enabled=false"
})
@Testcontainers
class TokenUsageIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /**
     * 确定性嵌入模型。
     *
     * <p>{@code spring.ai.model.embedding=none} 会让 PgVectorStore 缺依赖而**启动失败**
     * （不是降级），整个上下文起不来。这里补一个词袋实现，只为了让
     * {@code vectorStore} Bean 能构建 —— 本类不验证检索语义。
     */
    @TestConfiguration
    static class EmbeddingStub {
        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }

    @Autowired
    private TokenUsageRecorder recorder;

    @Autowired
    private TokenUsageRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ZoneId zoneId;

    /**
     * 库名断言：连错库时立刻失败，绝不执行清理。
     *
     * <p>放在 {@code @BeforeEach} 的第一行，因为清理本身就在 {@code @BeforeEach} 里。
     */
    @org.junit.jupiter.api.BeforeEach
    void guardDatabaseAndClean() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (!"test".equals(database)) {
            throw new IllegalStateException(
                    "拒绝在非测试库上执行清理（当前库：" + database + "）。"
                            + "本测试必须连接 Testcontainers 启动的库。");
        }
        jdbc.execute("DELETE FROM token_usage");
    }

    @Test
    @DisplayName("V15 迁移建出了表与索引（ddl-auto=validate 覆盖不到索引）")
    void migrationCreatedTableAndIndex() {
        Integer columns = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'token_usage'", Integer.class);
        Integer indexes = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'token_usage' "
                        + "and indexname = 'idx_token_usage_created_at'", Integer.class);

        assertThat(columns).isEqualTo(7);
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    @DisplayName("记录器把真实用量落库，source 标为 ACTUAL")
    void persistsActualUsage() {
        recorder.record("conv-actual", new StreamUsage("qwen3.8-flash", 3071, 5, TokenUsageSource.ACTUAL));

        List<TokenUsageEntity> rows = waitForRows(1);
        TokenUsageEntity row = rows.get(0);

        assertThat(row.getConversationId()).isEqualTo("conv-actual");
        assertThat(row.getModel()).isEqualTo("qwen3.8-flash");
        assertThat(row.getPromptTokens()).isEqualTo(3071);
        assertThat(row.getCompletionTokens()).isEqualTo(5);
        assertThat(row.getSource()).isEqualTo(TokenUsageSource.ACTUAL);
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("估算用量也能落库，且不会被误标成 ACTUAL")
    void persistsEstimatedUsage() {
        recorder.record("conv-est", StreamUsage.estimated(null, 1600, 160));

        TokenUsageEntity row = waitForRows(1).get(0);

        assertThat(row.getSource()).isEqualTo(TokenUsageSource.ESTIMATED);
        assertThat(row.getPromptTokens()).isEqualTo(1000);
        // 上游没给模型名时留空，不编造
        assertThat(row.getModel()).isNull();
    }

    @Test
    @DisplayName("零用量不写入（避免一行 0/0 的噪音记录）")
    void skipsMeaninglessUsage() {
        recorder.record("conv-zero", new StreamUsage("m", 0, 0, TokenUsageSource.ACTUAL));

        // 异步投递，等一小会儿确认没有写入
        sleepQuietly(300);

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("按日聚合用本地时区分组：跨 UTC 日界的记录归到本地那一天")
    @Transactional
    void aggregatesInLocalZone() {
        // 东八区 10-27 00:30 == UTC 10-26 16:30。
        // 若 SQL 按 UTC 截断，这条会被分到 10-26 —— 正是要防的错误
        Instant localEarlyMorning = java.time.LocalDate.of(2026, 10, 27)
                .atStartOfDay(zoneId).plusMinutes(30).toInstant();
        // 同一天的另一条（东八区 23:30），UTC 下也是 10-27 15:30
        Instant localLateNight = java.time.LocalDate.of(2026, 10, 27)
                .atTime(23, 30).atZone(zoneId).toInstant();

        recorder.persist("conv-a", new StreamUsage("m", 100, 10, TokenUsageSource.ACTUAL), localEarlyMorning);
        recorder.persist("conv-b", new StreamUsage("m", 200, 20, TokenUsageSource.ACTUAL), localLateNight);

        List<Object[]> rows = repository.aggregateByDay(
                localEarlyMorning.minusSeconds(86_400), localLateNight.plusSeconds(3600), zoneId.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("2026-10-27");
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(300);
        assertThat(((Number) rows.get(0)[4]).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("ACTUAL 与 ESTIMATED 在同一天聚合为两行（由服务层合并）")
    @Transactional
    void separatesSourcesInAggregation() {
        Instant at = Instant.now();
        recorder.persist("conv-x", new StreamUsage("m", 100, 10, TokenUsageSource.ACTUAL), at);
        recorder.persist("conv-y", new StreamUsage("m", 50, 5, TokenUsageSource.ESTIMATED), at);

        List<Object[]> rows = repository.aggregateByDay(at.minusSeconds(600), at.plusSeconds(600), zoneId.getId());

        // 按 source 分两行：服务层需要它们来算真实/估算占比，SQL 侧不该提前合并
        assertThat(rows).hasSize(2);
    }

    /** 等待异步写入完成；轮询而不是固定 sleep，避免慢机器上偶发失败 */
    private List<TokenUsageEntity> waitForRows(int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<TokenUsageEntity> rows = repository.findAll();
            if (rows.size() >= expected) {
                return rows;
            }
            sleepQuietly(50);
        }
        return repository.findAll();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
