package com.personalos.list;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 列表分页与站内搜索（阶段 16）。
 *
 * <p>同时覆盖两个模块，因为它们的契约必须一致（同一套分页收敛口径）。
 * 嵌入模型替换为确定性词袋实现，因此不需要网络；但向量库仍是真实的
 * —— 文档分页路径会真的写进 pgvector。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=none",
                "app.calendar.scan-enabled=false"})
@Testcontainers
class ListSearchIntegrationTest {

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
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ListSearchIntegrationTest(@Value("${local.server.port}") int port,
                              JdbcTemplate jdbcTemplate,
                              KnowledgeDocumentService documentService) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE chat_session, chat_message, knowledge_document,"
                + " vector_store, task, task_tag, calendar_event, reminder,"
                + " memory, operation_record RESTART IDENTITY CASCADE");
    }

    private JsonNode get(String path) throws Exception {
        HttpTestClient.Response response = http.get(path);
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private List<String> titles(JsonNode items) {
        List<String> result = new ArrayList<>();
        items.forEach(item -> result.add(item.path("title").asText()));
        return result;
    }

    private void createSessions(int count) throws Exception {
        for (int index = 0; index < count; index += 1) {
            assertThat(http.post("/api/v1/chat/sessions",
                    "{\"title\":\"会话-" + String.format("%02d", index) + "\"}").status())
                    .isEqualTo(201);
        }
    }

    // ---------------- 会话 ----------------

    @Test
    @DisplayName("会话分页：第 1 页 20 条、total=25、两页不重复")
    void shouldPageSessions() throws Exception {
        createSessions(25);

        JsonNode first = get("/api/v1/chat/sessions/search?page=0&size=20");
        assertThat(first.path("total").asLong()).isEqualTo(25);
        assertThat(first.path("items").size()).isEqualTo(20);

        JsonNode second = get("/api/v1/chat/sessions/search?page=1&size=20");
        assertThat(second.path("items").size()).isEqualTo(5);

        Set<String> all = new HashSet<>();
        first.path("items").forEach(item -> all.add(item.path("conversationId").asText()));
        second.path("items").forEach(item -> all.add(item.path("conversationId").asText()));
        assertThat(all).as("两页合起来应恰好 25 个不重复会话").hasSize(25);
    }

    @Test
    @DisplayName("会话搜索：只返回标题匹配的会话；无结果时 total=0")
    void shouldSearchSessions() throws Exception {
        createSessions(3);
        assertThat(http.post("/api/v1/chat/sessions", "{\"title\":\"关于周报的讨论\"}").status())
                .isEqualTo(201);

        JsonNode hit = get("/api/v1/chat/sessions/search?keyword="
                + URLEncoder.encode("周报", StandardCharsets.UTF_8));
        assertThat(hit.path("total").asLong()).isEqualTo(1);
        assertThat(hit.path("items").get(0).path("title").asText()).isEqualTo("关于周报的讨论");

        JsonNode miss = get("/api/v1/chat/sessions/search?keyword="
                + URLEncoder.encode("不存在的词", StandardCharsets.UTF_8));
        assertThat(miss.path("total").asLong()).isZero();
        assertThat(miss.path("items")).isEmpty();
    }

    @Test
    @DisplayName("会话：size 越界收敛；越界 page 返回空 items 但 total 正确")
    void shouldClampSessionParams() throws Exception {
        createSessions(3);

        JsonNode clamped = get("/api/v1/chat/sessions/search?size=1000");
        assertThat(clamped.path("size").asInt()).isEqualTo(100);
        assertThat(clamped.path("items").size()).isEqualTo(3);

        JsonNode zeroSize = get("/api/v1/chat/sessions/search?size=0");
        assertThat(zeroSize.path("size").asInt()).isEqualTo(20);

        JsonNode outOfRange = get("/api/v1/chat/sessions/search?page=999&size=20");
        assertThat(outOfRange.path("items")).isEmpty();
        assertThat(outOfRange.path("total").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("会话：翻页后每行的消息条数仍然正确（只对当前页做分组查询）")
    void shouldKeepMessageCountsAcrossPages() throws Exception {
        createSessions(22);
        // 给第一个会话发两条消息
        HttpTestClient.Response created = http.post("/api/v1/chat/sessions", "{\"title\":\"有很多消息\"}");
        String conversationId = mapper.readTree(created.body()).path("data").path("conversationId").asText();
        for (int index = 0; index < 2; index += 1) {
            // 直接写消息（不需要模型：用 /stream 会调用 ChatModel）
            jdbcTemplate.update(
                    "INSERT INTO chat_message (conversation_id, sequence_no, message_type, content, created_at) "
                            + "VALUES (?, ?, ?, ?, now())",
                    conversationId, index, index == 0 ? "USER" : "ASSISTANT", "第" + index + "条");
        }

        // 该会话最近活动，应排在第 1 页
        JsonNode first = get("/api/v1/chat/sessions/search?page=0&size=20");
        JsonNode items = first.path("items");
        JsonNode target = null;
        for (int index = 0; index < items.size(); index += 1) {
            JsonNode item = items.get(index);
            if (conversationId.equals(item.path("conversationId").asText())) {
                target = item;
            }
        }
        assertThat(target).as("刚发过消息的会话应排在第 1 页").isNotNull();
        assertThat(target.path("messageCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("原全量端点保持不变（导出链路与既有测试依赖它）")
    void shouldKeepFullListEndpoint() throws Exception {
        createSessions(25);

        JsonNode all = get("/api/v1/chat/sessions");

        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isEqualTo(25);
    }

    // ---------------- 知识库 ----------------

    private void ingest(String title, String filename) throws Exception {
        String markdown = "# " + title + "\n\n这是关于「" + title + "」的说明正文。\n";
        documentService.ingest(filename, title, markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("文档分页：第 1 页 20 条、total=25、翻页不重复")
    void shouldPageDocuments() throws Exception {
        for (int index = 0; index < 25; index += 1) {
            ingest("文档-" + String.format("%02d", index), "file-" + String.format("%02d", index) + ".md");
        }

        JsonNode first = get("/api/v1/knowledge/documents/search?page=0&size=20");
        assertThat(first.path("total").asLong()).isEqualTo(25);
        assertThat(first.path("items").size()).isEqualTo(20);

        JsonNode second = get("/api/v1/knowledge/documents/search?page=1&size=20");
        assertThat(second.path("items").size()).isEqualTo(5);

        Set<String> ids = new HashSet<>();
        first.path("items").forEach(item -> ids.add(item.path("id").asText()));
        second.path("items").forEach(item -> ids.add(item.path("id").asText()));
        assertThat(ids).hasSize(25);
    }

    @Test
    @DisplayName("文档搜索：标题命中；文件名命中（标题不同也要能搜到）")
    void shouldSearchDocuments() throws Exception {
        ingest("植物养护笔记", "notes-green.md");
        ingest("数据库事务", "isolation.md");

        JsonNode byTitle = get("/api/v1/knowledge/documents/search?keyword="
                + URLEncoder.encode("植物", StandardCharsets.UTF_8));
        assertThat(byTitle.path("total").asLong()).isEqualTo(1);
        assertThat(titles(byTitle.path("items"))).containsExactly("植物养护笔记");

        // 标题里没有 "isolation"，只有文件名有 —— 这是只匹配标题时会漏掉的情况
        JsonNode byFilename = get("/api/v1/knowledge/documents/search?keyword="
                + URLEncoder.encode("isolation", StandardCharsets.UTF_8));
        assertThat(byFilename.path("total").asLong()).isEqualTo(1);
        assertThat(byFilename.path("items").get(0).path("filename").asText()).isEqualTo("isolation.md");
    }

    @Test
    @DisplayName("文档：size 越界收敛；无结果时 total=0")
    void shouldClampDocumentParams() throws Exception {
        ingest("植物养护笔记", "notes-green.md");

        JsonNode clamped = get("/api/v1/knowledge/documents/search?size=1000");
        assertThat(clamped.path("size").asInt()).isEqualTo(100);

        JsonNode zeroSize = get("/api/v1/knowledge/documents/search?size=0");
        assertThat(zeroSize.path("size").asInt()).isEqualTo(20);

        JsonNode miss = get("/api/v1/knowledge/documents/search?keyword="
                + URLEncoder.encode("红烧肉", StandardCharsets.UTF_8));
        assertThat(miss.path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("文档：原全量端点保持不变")
    void shouldKeepFullDocumentEndpoint() throws Exception {
        for (int index = 0; index < 3; index += 1) {
            ingest("文档-" + index, "file-" + index + ".md");
        }

        JsonNode all = get("/api/v1/knowledge/documents");

        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isEqualTo(3);
    }
}
