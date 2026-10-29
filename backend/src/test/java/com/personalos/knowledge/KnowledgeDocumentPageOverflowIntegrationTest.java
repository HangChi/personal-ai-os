package com.personalos.knowledge;

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
 * 知识库列表的页码溢出：必须返回空页，不能是 500。
 *
 * <h3>为什么这条用例值得单独存在</h3>
 * 这个 bug 已经被修过一次（会话搜索 + 工具审计），防线是**逐个服务手写**的
 * {@code PageDto.offsetOverflows} 判断。2026-10-29 端到端日志里它第三次出现：
 *
 * <pre>
 * 10:35:09 ERROR 未捕获异常，已按内部错误处理
 *   InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE (2147483647)
 * </pre>
 *
 * <p>原因就是知识库列表当时没被点名。**凡是「靠人记得在每个地方加一行」的防护，
 * 就一定会漏** —— 所以这里用真实 HTTP 请求把该接口钉住，将来新增分页接口时
 * 照这个用例补一个即可。
 *
 * <p>页码是纯客户端输入，不该有能力制造 500：{@code page=21474837&size=100}
 * 乘积超过 int 上界，Spring Data 算 offset 时溢出 → 抛异常 → 落到全局兜底变 500。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false"
})
@Testcontainers
class KnowledgeDocumentPageOverflowIntegrationTest {

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
    KnowledgeDocumentPageOverflowIntegrationTest(@Value("${local.server.port}") int port,
                                                JdbcTemplate jdbcTemplate) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE knowledge_document RESTART IDENTITY CASCADE");
    }

    /**
     * 插一条文档，直接走 JDBC。
     *
     * <p>不走上传接口：那条路径要过解析与分块，构造一个「只要能让 total 不为 0」的
     * 前置数据没必要这么重。这里要验的是**分页参数**，不是入库。
     *
     * <p>列必须与 V4 迁移严格对齐：表里**没有 status 列**，而 {@code char_count}
     * 是 NOT NULL 且无默认值 —— 漏了它插入直接失败，且报错信息是 PG 的列名约束，
     * 读起来像「测试环境没建好表」，容易误判。
     */
    private void insertDocument(String title) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_document
                    (title, filename, content_type, size_bytes, char_count, chunk_count)
                VALUES (?, ?, 'text/plain', 100, 500, 1)
                """, title, title + ".txt");
    }

    @Test
    @DisplayName("页码溢出：返回 200 + 空页，total 仍为真实条数，而不是 500")
    void hugePageReturnsEmptyPageNotServerError() throws Exception {
        insertDocument("唯一的一份文档");

        // 21474837 * 100 超过 int 上界（2147483647）；用 int 相乘会溢出成负数。
        // size 会被收敛到上限 100，但 21474837 * 100 依然溢出。
        HttpTestClient.Response response = http.get("/api/v1/knowledge/documents/search?page=21474837&size=100");

        assertThat(response.status())
                .as("页码溢出必须返回 200；500 说明 offsetOverflows 防护又漏了。响应体：%s", response.body())
                .isEqualTo(200);

        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("items").size()).isZero();
        // total 必须是真实值：前端靠它渲染分页器，返回 0 会显示「一份文档都没有」
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("带关键词时同样不炸：count 走的是带 spec 的分支")
    void hugePageWithKeywordAlsoSafe() throws Exception {
        insertDocument("年度总结");

        HttpTestClient.Response response =
                http.get("/api/v1/knowledge/documents/search?keyword=%E5%B9%B4%E5%BA%A6&page=21474837&size=100");

        assertThat(response.status()).as("响应体：%s", response.body()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("items").size()).isZero();
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("正常范围内的深页码照旧返回空页且 200")
    void largeButValidPageStillWorks() throws Exception {
        insertDocument("普通文档");

        // 未溢出的深页码：这是正常用户行为，不该被防overflow那套逻辑误伤
        HttpTestClient.Response response = http.get("/api/v1/knowledge/documents/search?page=9999&size=20");

        assertThat(response.status()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("items").size()).isZero();
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }
}
