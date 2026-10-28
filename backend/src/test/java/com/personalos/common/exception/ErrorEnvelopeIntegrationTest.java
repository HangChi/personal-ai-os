package com.personalos.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 错误信封的对外契约：**客户端传错内容，不能报成 500**。
 *
 * <p>这组用例守的是三件容易悄悄退化的事：
 * <ul>
 *   <li>畸形 JSON / 不支持的请求方法落到兜底分支 → 用户看到"服务器内部错误"，
 *       前端 5xx 拦截器还会把它当服务端故障；</li>
 *   <li>日志被客户端输入刷成 error 级，真正的服务端异常被淹没；</li>
 *   <li>分页参数越界时不是返回空页，而是抛 {@code IndexOutOfBoundsException}。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false"
})
@Testcontainers
class ErrorEnvelopeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new DeterministicEmbeddingModel(1024);
        }
    }

    private final HttpTestClient http;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ErrorEnvelopeIntegrationTest(@Value("${local.server.port}") int port, JdbcTemplate jdbcTemplate) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE task, task_tag RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("畸形 JSON：400 + 参数校验失败，而不是 500")
    void malformedJsonIsClientError() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/tasks", "{\"title\": ");

        // 断言里带上响应体：这三条用例曾偶发返回 404（整个应用层都没接上的样子），
        // 只断言状态码的话，失败时无从判断 404 到底是谁返回的。40004 是本应用的
        // 「资源不存在」，别的结构就说明请求压根没打到这个应用。
        assertThat(response.status()).as("POST /api/v1/tasks 的响应：%s", response.body()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(40000);
        assertThat(body.path("message").asString()).contains("请求体格式错误");
    }

    @Test
    @DisplayName("请求方法不被支持：405 + 40005，而不是 500")
    void methodNotAllowedIsClientError() throws Exception {
        // /chat/uploads 只接受 POST；这里刻意用 GET
        HttpTestClient.Response response = http.get("/api/v1/chat/uploads");

        assertThat(response.status()).as("GET /api/v1/chat/uploads 的响应：%s", response.body()).isEqualTo(405);
        assertThat(mapper.readTree(response.body()).path("code").asInt()).isEqualTo(40005);
    }

    @Test
    @DisplayName("页码溢出：返回空页，而不是 subList 负下标导致的 500")
    void hugePageNumberReturnsEmptyPage() throws Exception {
        HttpTestClient.Response created = http.post("/api/v1/tasks", "{\"title\":\"唯一的一条\"}");
        assertThat(created.status()).as("创建任务的响应：%s", created.body()).isEqualTo(201);

        // 21474837 * 100 超过 int 上界；用 int 计算会溢出成负数。
        // 这条请求以前能稳定打出 500，且不需要任何特殊权限。
        HttpTestClient.Response response = http.get("/api/v1/tasks?page=21474837&size=100");

        assertThat(response.status()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("items").size()).isZero();
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }
}
