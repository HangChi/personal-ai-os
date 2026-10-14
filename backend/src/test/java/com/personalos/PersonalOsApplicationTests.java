package com.personalos;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.mcp.client.McpClientRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 集成测试：验证「上下文可装配 + Flyway 在真实 PostgreSQL 上完成迁移 + HTTP 层返回统一结构」。
 *
 * <p>使用 Testcontainers 拉起与 {@code compose.yml} 相同的
 * {@code pgvector/pgvector:pg17} 镜像，因此测试是自包含的（不依赖外部已启动的数据库），
 * 同时能真实验证 {@code CREATE EXTENSION vector} 这类 H2 无法覆盖的行为。
 *
 * <p>前置条件：本机 Docker 可用。Docker 不可用时本测试会失败，这是有意为之——
 * 迁移正确性是阶段 0 的核心主张，不应被静默跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PersonalOsApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationContext context;
    private final int port;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    PersonalOsApplicationTests(JdbcTemplate jdbcTemplate,
                               ApplicationContext context,
                               @Value("${local.server.port}") int port) {
        this.jdbcTemplate = jdbcTemplate;
        this.context = context;
        this.port = port;
    }

    @Test
    @DisplayName("Flyway 在真实 PostgreSQL 上执行了 V1 迁移，且 pgvector 扩展可用")
    void flywayMigrationAppliesVectorExtension() {
        Integer vectorExtensionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(vectorExtensionCount).isEqualTo(1);

        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("create extension vector 之后可以真正创建向量列（证明 pgvector 生效）")
    void pgvectorIsFunctional() {
        jdbcTemplate.execute("CREATE TEMP TABLE phase0_vector_probe (embedding vector(3))");
        jdbcTemplate.update("INSERT INTO phase0_vector_probe (embedding) VALUES ('[1,2,3]')");

        String rendered = jdbcTemplate.queryForObject(
                "SELECT embedding::text FROM phase0_vector_probe LIMIT 1", String.class);
        assertThat(rendered).contains("1").contains("2").contains("3");
    }

    @Test
    @DisplayName("GET /api/v1/health 返回 HTTP 200 与统一响应结构")
    void healthEndpointReturnsUnifiedEnvelope() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        assertThat(response.body())
                .contains("\"code\":0")
                .contains("\"status\":\"UP\"")
                .contains("personal-ai-os")
                // 契约固定为 4 个字段（code/message/data/timestamp）。
                // 不加此断言的话，Jackson 会把 record 的 isSuccess() 当成 getter，
                // 凭空多序列化一个 success 字段，而所有「包含式」断言依然全绿。
                .doesNotContain("\"success\"");
    }

    @Test
    @DisplayName("未配置 MCP：不创建 McpClientRegistry（零启用默认，与阶段 5 之前行为一致）")
    void mcpRegistryAbsentWhenNotConfigured() {
        assertThat(context.getBeanNamesForType(McpClientRegistry.class)).isEmpty();
    }

    @Test
    @DisplayName("不存在的路径返回统一错误结构，且不泄漏堆栈/类名")
    void unknownPathReturnsUnifiedErrorEnvelope() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/definitely-not-exist"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("\"code\":40004")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("at org.springframework");
    }
}
