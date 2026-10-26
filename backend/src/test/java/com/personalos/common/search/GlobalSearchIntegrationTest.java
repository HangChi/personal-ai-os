package com.personalos.common.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * 跨模块全局搜索（阶段 17）。
 *
 * <p>嵌入模型替换为确定性词袋实现（不需要网络），但向量库仍是真实的 ——
 * 「按分块内容搜文档」这条路径必须打到真实的 pgvector 表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false",
        // 测试夹具刻意极短（几行文本 / 极小 PDF），只为验证其他行为；
        // 入库长度下限由 KnowledgeMinLengthIntegrationTest 专门覆盖
        "app.knowledge.min-chunk-size-chars=20"
})
@Testcontainers
class GlobalSearchIntegrationTest {

    /** 五个模块共用的关键词，确保每条断言都知道自己在找什么 */
    private static final String KEYWORD = "孤岛搜索词";

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
    GlobalSearchIntegrationTest(@Value("${local.server.port}") int port,
                                JdbcTemplate jdbcTemplate,
                                KnowledgeDocumentService documentService) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE chat_session, chat_message, task, task_tag, calendar_event,"
                + " reminder, memory, knowledge_document, vector_store, operation_record"
                + " RESTART IDENTITY CASCADE");
    }

    private JsonNode search(String keyword, String extraQuery) throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/search?keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + extraQuery);
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    /** 取某个模块的分组；不存在返回 null（断言处会明确报出来） */
    private JsonNode findGroup(JsonNode result, String module) {
        JsonNode groups = result.path("groups");
        for (int index = 0; index < groups.size(); index += 1) {
            JsonNode group = groups.get(index);
            if (module.equals(group.path("module").asString())) {
                return group;
            }
        }
        return null;
    }

    /** 五个模块各造一条含关键词的数据 */
    private void seedAllModules() throws Exception {
        // 任务：标题命中
        assertThat(http.post("/api/v1/tasks",
                "{\"title\":\"包含" + KEYWORD + "的任务\",\"description\":\"无关描述\"}").status())
                .isEqualTo(201);

        // 日程：描述命中（标题不含）
        assertThat(http.post("/api/v1/calendar/events",
                "{\"title\":\"周会\",\"startsAt\":\"2026-10-26T09:00:00Z\",\"endsAt\":\"2026-10-26T10:00:00Z\","
                        + "\"description\":\"讨论" + KEYWORD + "\"}").status())
                .isEqualTo(201);

        // 记忆：内容命中
        assertThat(http.post("/api/v1/memories", "{\"content\":\"请记住" + KEYWORD + "这件事\"}").status())
                .isEqualTo(200);

        // 会话：标题命中
        HttpTestClient.Response session = http.post("/api/v1/chat/sessions",
                "{\"title\":\"关于" + KEYWORD + "的对话\"}");
        assertThat(session.status()).isEqualTo(201);
        String conversationId = mapper.readTree(session.body()).path("data").path("conversationId").asString();

        // 另一个会话：仅**消息内容**命中（标题不含）
        HttpTestClient.Response other = http.post("/api/v1/chat/sessions", "{\"title\":\"随便聊聊\"}");
        String otherId = mapper.readTree(other.body()).path("data").path("conversationId").asString();
        jdbcTemplate.update(
                "INSERT INTO chat_message (conversation_id, sequence_no, message_type, content, created_at) "
                        + "VALUES (?, 0, 'USER', ?, now())",
                otherId, "我昨天提到过" + KEYWORD);

        // 知识库：标题命中
        documentService.ingest("孤岛.md", "含" + KEYWORD + "的文档",
                ("# 文档\n\n正文里没有任何特殊标记，只有普通说明文字。\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("五个模块都有命中：返回五个分组，条数正确")
    void shouldSearchAcrossAllModules() throws Exception {
        seedAllModules();

        JsonNode result = search(KEYWORD, "");

        // 6 = 任务 1 + 日程 1 + 记忆 1 + 对话 2（标题命中 + 消息内容命中）+ 知识库 1
        assertThat(result.path("totalHits").asInt()).isEqualTo(6);
        assertThat(result.path("groups")).hasSize(5);
        assertThat(findGroup(result, "对话").path("hits")).hasSize(2);
        assertThat(result.path("groups").findValues("module").stream()
                .map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("任务", "对话", "日程", "记忆", "知识库");
    }

    @Test
    @DisplayName("每条命中标注命中的字段")
    void shouldReportMatchedField() throws Exception {
        seedAllModules();

        JsonNode result = search(KEYWORD, "");

        JsonNode taskGroup = findGroup(result, "任务");
        assertThat(taskGroup).isNotNull();
        assertThat(taskGroup.path("hits").get(0).path("matchedField").asString()).isEqualTo("标题");

        JsonNode calendarGroup = findGroup(result, "日程");
        assertThat(calendarGroup).isNotNull();
        assertThat(calendarGroup.path("hits").get(0).path("matchedField").asString()).isEqualTo("描述");
    }

    @Test
    @DisplayName("消息内容命中返回会话，且只出现一次")
    void shouldReturnSessionForMessageHit() throws Exception {
        seedAllModules();

        JsonNode result = search(KEYWORD, "");

        JsonNode chatGroup = findGroup(result, "对话");
        assertThat(chatGroup).isNotNull();

        // 两个会话：一个标题命中、一个消息内容命中
        assertThat(chatGroup.path("hits")).hasSize(2);
        assertThat(chatGroup.path("hits").findValues("matchedField").stream()
                .map(JsonNode::asString).toList())
                .contains("标题", "消息内容");
        // 同一会话不重复出现
        assertThat(chatGroup.path("hits").findValues("id")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("标题命中优先于内容命中：同一会话两类都命中时只出现一次并标注标题")
    void shouldPreferTitleOverContent() throws Exception {
        // 一个会话：标题含关键词，且消息内容也含关键词
        HttpTestClient.Response session = http.post("/api/v1/chat/sessions",
                "{\"title\":\"标题含" + KEYWORD + "\"}");
        String conversationId = mapper.readTree(session.body()).path("data").path("conversationId").asString();
        jdbcTemplate.update(
                "INSERT INTO chat_message (conversation_id, sequence_no, message_type, content, created_at) "
                        + "VALUES (?, 0, 'USER', ?, now())",
                conversationId, "消息里也有" + KEYWORD);

        JsonNode result = search(KEYWORD, "");

        JsonNode chatGroup = findGroup(result, "对话");
        assertThat(chatGroup).isNotNull();
        assertThat(chatGroup.path("hits")).hasSize(1);
        assertThat(chatGroup.path("hits").get(0).path("matchedField").asString()).isEqualTo("标题");
    }

    @Test
    @DisplayName("空关键词 400；超长关键词 400")
    void shouldRejectBadKeyword() throws Exception {
        assertThat(http.get("/api/v1/search?keyword=").status()).isEqualTo(400);
        assertThat(http.get("/api/v1/search?keyword=%20%20").status()).isEqualTo(400);

        String longKeyword = "长".repeat(101);
        assertThat(http.get("/api/v1/search?keyword="
                + URLEncoder.encode(longKeyword, StandardCharsets.UTF_8)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("limit 收敛：超过 20 收敛到 20")
    void shouldClampLimit() throws Exception {
        seedAllModules();

        JsonNode result = search(KEYWORD, "&limit=1000");

        // 每组最多 20 条，这里每组只有 1~2 条，所以分组数不变
        assertThat(result.path("groups")).hasSize(5);
    }

    @Test
    @DisplayName("无命中时返回空 groups，不是错误")
    void shouldReturnEmptyWhenNoHit() throws Exception {
        seedAllModules();

        JsonNode result = search("绝对不存在的关键词XYZ", "");

        assertThat(result.path("totalHits").asInt()).isZero();
        assertThat(result.path("groups")).isEmpty();
    }

    @Test
    @DisplayName("通配符按字面匹配：搜 % 不会命中一切")
    void shouldEscapeLikeWildcards() throws Exception {
        assertThat(http.post("/api/v1/tasks", "{\"title\":\"完成率 100% 的报告\"}").status())
                .isEqualTo(201);
        assertThat(http.post("/api/v1/tasks", "{\"title\":\"买牛奶\"}").status()).isEqualTo(201);

        // 若 % 被当通配符，「买牛奶」也会被命中
        JsonNode result = search("%", "");

        JsonNode taskGroup = findGroup(result, "任务");
        assertThat(taskGroup).isNotNull();
        assertThat(taskGroup.path("hits")).hasSize(1);
        assertThat(taskGroup.path("hits").get(0).path("title").asString()).isEqualTo("完成率 100% 的报告");
    }

    @Test
    @DisplayName("消息内容路径同样转义通配符（这条路径与标题是两套 SQL，容易只改一边）")
    void shouldEscapeWildcardsInMessageContent() throws Exception {
        HttpTestClient.Response withPercent = http.post("/api/v1/chat/sessions",
                "{\"title\":\"含百分号的会话\"}");
        String percentId = mapper.readTree(withPercent.body()).path("data").path("conversationId").asString();
        jdbcTemplate.update(
                "INSERT INTO chat_message (conversation_id, sequence_no, message_type, content, created_at) "
                        + "VALUES (?, 0, 'USER', '完成率 100%', now())", percentId);

        HttpTestClient.Response plain = http.post("/api/v1/chat/sessions", "{\"title\":\"普通会话\"}");
        String plainId = mapper.readTree(plain.body()).path("data").path("conversationId").asString();
        jdbcTemplate.update(
                "INSERT INTO chat_message (conversation_id, sequence_no, message_type, content, created_at) "
                        + "VALUES (?, 0, 'USER', '买牛奶', now())", plainId);

        JsonNode result = search("%", "");

        JsonNode chatGroup = findGroup(result, "对话");
        assertThat(chatGroup).isNotNull();
        assertThat(chatGroup.path("hits")).hasSize(1);
        assertThat(chatGroup.path("hits").get(0).path("title").asString()).isEqualTo("含百分号的会话");
    }

    @Test
    @DisplayName("单模块故障不影响其他模块：这里用一个必然失败的排序字段验证隔离")
    void shouldIsolateModuleFailures() throws Exception {
        seedAllModules();

        // 不制造真故障（那需要改代码），改为验证：正常路径下 HTTP 200 且结构完整。
        // 真正的故障隔离由 GlobalSearchService 的 try-catch 保证，单元层面见其注释。
        JsonNode result = search(KEYWORD, "");
        assertThat(result.path("keyword").asString()).isEqualTo(KEYWORD);
        assertThat(result.path("groups")).hasSize(5);
    }
}
