package com.personalos.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * 数据导出集成测试。
 *
 * <h2>为什么用真实 pgvector 而不 mock</h2>
 * 知识库导出依赖 {@code VectorStoreInspector} 从 {@code vector_store} 读分块文本，
 * mock 掉就等于不测「原文不落库、只能靠分块还原」这条唯一路径。
 * 嵌入模型替换为确定性的词袋实现，因此仍然不依赖网络。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false"
})
@Testcontainers
class DataExportIntegrationTest {

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
    private final KnowledgeDocumentService documentService;
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    DataExportIntegrationTest(@Value("${local.server.port}") int port,
                              JdbcTemplate jdbcTemplate,
                              KnowledgeDocumentService documentService) {
        this.port = port;
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
    }

    @BeforeEach
    void cleanUp() {
        // 显式列出而不是 TRUNCATE ... CASCADE 整库：CASCADE 会把 flyway_schema_history 之外的
        // 依赖表一起清掉，将来加表时行为会悄悄变化
        jdbcTemplate.execute("TRUNCATE chat_message, chat_session, task, calendar_event, reminder,"
                + " memory, vector_store, knowledge_document, confirmation, operation_record"
                + " RESTART IDENTITY CASCADE");
    }

    private JsonNode export() throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/export");
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private void createTask(String title) throws Exception {
        assertThat(http.post("/api/v1/tasks", "{\"title\":\"" + title + "\"}").status()).isEqualTo(201);
    }

    private void createEvent(String title) throws Exception {
        String body = "{\"title\":\"" + title + "\","
                + "\"startsAt\":\"2026-10-26T09:00:00Z\","
                + "\"endsAt\":\"2026-10-26T10:00:00Z\"}";
        assertThat(http.post("/api/v1/calendar/events", body).status()).isEqualTo(201);
    }

    private void createMemory(String content) throws Exception {
        assertThat(http.post("/api/v1/memories", "{\"content\":\"" + content + "\"}").status()).isEqualTo(200);
    }

    private void createSession(String title) throws Exception {
        assertThat(http.post("/api/v1/chat/sessions", "{\"title\":\"" + title + "\"}").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("空库：仍是合法 JSON，各数组为空、counts 全 0")
    void shouldExportEmptyDatabase() throws Exception {
        JsonNode body = export();

        assertThat(body.path("application").asText()).isEqualTo("personal-ai-os");
        assertThat(body.path("formatVersion").asText()).isEqualTo(DataExportService.FORMAT_VERSION);
        assertThat(body.path("exportedAt").asText()).isNotBlank();

        for (String key : new String[]{"sessions", "tasks", "calendarEvents", "memories",
                "knowledgeDocuments"}) {
            assertThat(body.path(key).isArray()).as("%s 应当是数组", key).isTrue();
            assertThat(body.path(key)).as("%s 应为空", key).isEmpty();
            assertThat(body.path("counts").path(key).asInt()).as("%s 计数应为 0", key).isZero();
        }
    }

    @Test
    @DisplayName("各模块计数与数组长度、与库中实际条数三者一致")
    void shouldKeepCountsConsistentWithArrays() throws Exception {
        createTask("写周报");
        createTask("买牛奶");
        createTask("整理教案");
        createEvent("周会");
        createMemory("我常住扬州");
        createSession("关于任务的对话");

        JsonNode body = export();

        assertThat(body.path("counts").path("tasks").asInt()).isEqualTo(3);
        assertThat(body.path("tasks").size()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(3);

        assertThat(body.path("counts").path("calendarEvents").asInt()).isEqualTo(1);
        assertThat(body.path("calendarEvents").size()).isEqualTo(1);

        assertThat(body.path("counts").path("memories").asInt()).isEqualTo(1);
        assertThat(body.path("memories").size()).isEqualTo(1);

        assertThat(body.path("counts").path("sessions").asInt()).isEqualTo(1);
        assertThat(body.path("sessions").size()).isEqualTo(1);
        // 会话内嵌套 messages，且新会话没有消息
        assertThat(body.path("sessions").get(0).path("messages").isArray()).isTrue();
        assertThat(body.path("sessions").get(0).path("messages").isEmpty()).isTrue();
        assertThat(body.path("sessions").get(0).path("session").path("conversationId").asText()).isNotBlank();

        // 任务字段要真在（不是只有计数）
        assertThat(body.path("tasks").get(0).path("title").asText()).isNotBlank();
        assertThat(body.path("tasks").get(0).path("status").asText()).isEqualTo("TODO");
    }

    @Test
    @DisplayName("知识库：导出分块文本（原文不落库，这是唯一可还原形态）")
    void shouldExportKnowledgeChunks() throws Exception {
        String markdown = "# 数据库事务\n\n"
                + "PostgreSQL 的默认隔离级别是读已提交。串行化最严格，但并发性能最低。\n";
        documentService.ingest("transaction.md", "数据库事务", markdown.getBytes(StandardCharsets.UTF_8));

        JsonNode body = export();

        assertThat(body.path("counts").path("knowledgeDocuments").asInt()).isEqualTo(1);
        JsonNode document = body.path("knowledgeDocuments").get(0);
        assertThat(document.path("document").path("title").asText()).isEqualTo("数据库事务");
        assertThat(document.path("document").path("filename").asText()).isEqualTo("transaction.md");
        assertThat(document.path("document").path("charCount").asInt()).isGreaterThan(0);

        JsonNode chunks = document.path("chunks");
        assertThat(chunks.isArray()).isTrue();
        assertThat(chunks.isEmpty()).isFalse();
        StringBuilder joined = new StringBuilder();
        chunks.forEach(chunk -> joined.append(chunk.asText()));
        assertThat(joined.toString()).contains("隔离级别");
    }

    @Test
    @DisplayName("响应头：当作附件下载，文件名带时间戳")
    void shouldSetAttachmentHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/export"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment")
                .contains("personal-ai-os-export-")
                .contains(".json");
    }

    @Test
    @DisplayName("导出不含向量：向量是派生物，带上会让文件膨胀且换模型即失效")
    void shouldNotExportEmbeddings() throws Exception {
        String markdown = "# 植物养护\n\n绿萝喜欢散射光，浇水见干见湿。\n";
        documentService.ingest("plant.md", "植物养护", markdown.getBytes(StandardCharsets.UTF_8));

        HttpTestClient.Response response = http.get("/api/v1/export");

        assertThat(response.body()).doesNotContain("\"embedding\"");
        // 分块文本在，向量不在
        assertThat(response.body()).contains("绿萝喜欢散射光");
    }
}
