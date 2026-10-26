package com.personalos.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.support.HttpTestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 任务标签集成测试（阶段 14）。
 *
 * <p>走真实 REST 而不是直接调 Service：标签是数组字段，它的 JSON 形状本身就是契约的一部分
 * （`tags: []` 与 `tags` 缺失在语义上必须可区分）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "app.calendar.scan-enabled=false"})
@Testcontainers
class TaskTagsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HttpTestClient http;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    TaskTagsIntegrationTest(@Value("${local.server.port}") int port, JdbcTemplate jdbcTemplate) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE task RESTART IDENTITY CASCADE");
    }

    private JsonNode create(String title, String tagsJson) throws Exception {
        String body = "{\"title\":\"" + title + "\"" + (tagsJson == null ? "" : ",\"tags\":" + tagsJson) + "}";
        HttpTestClient.Response response = http.post("/api/v1/tasks", body);
        assertThat(response.status()).isEqualTo(201);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode patch(String id, String body) throws Exception {
        HttpTestClient.Response response = http.patch("/api/v1/tasks/" + id, body, Map.of());
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode listTasks(String query) throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/tasks" + query);
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    /** 手动遍历而不是 convertValue：Jackson 3 的 TypeFactory API 与 2.x 有差异，不值得为测试引入 */
    private List<String> tagList(JsonNode array) {
        List<String> result = new java.util.ArrayList<>();
        array.forEach(node -> result.add(node.asString()));
        return result;
    }

    @Test
    @DisplayName("创建时带标签：顺序与输入一致")
    void shouldKeepTagOrderOnCreate() throws Exception {
        JsonNode created = create("写周报", "[\"工作\",\"本周\"]");

        assertThat(tagList(created.path("tags"))).containsExactly("工作", "本周");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task_tag", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("更新标签：整体替换而不是追加")
    void shouldReplaceTags() throws Exception {
        JsonNode created = create("写周报", "[\"工作\",\"本周\"]");

        JsonNode updated = patch(created.path("id").asString(), "{\"tags\":[\"学习\"]}");

        assertThat(tagList(updated.path("tags"))).containsExactly("学习");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task_tag", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("传空数组：清空标签（不是「没改动」）")
    void shouldClearTagsWithEmptyArray() throws Exception {
        JsonNode created = create("写周报", "[\"工作\"]");

        JsonNode updated = patch(created.path("id").asString(), "{\"tags\":[]}");

        assertThat(updated.path("tags").isEmpty()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task_tag", Integer.class)).isZero();
    }

    @Test
    @DisplayName("不传 tags：标签保持不变")
    void shouldKeepTagsWhenOmitted() throws Exception {
        JsonNode created = create("写周报", "[\"工作\"]");

        JsonNode updated = patch(created.path("id").asString(), "{\"title\":\"写周报（改）\"}");

        assertThat(updated.path("title").asString()).isEqualTo("写周报（改）");
        assertThat(tagList(updated.path("tags"))).containsExactly("工作");
    }

    @Test
    @DisplayName("6 个标签：400 + 明确文案，不静默截断")
    void shouldRejectTooManyTags() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/tasks",
                "{\"title\":\"写周报\",\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}");

        assertThat(response.status()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(40000);
        assertThat(body.path("message").asString()).contains("最多 5 个");
    }

    @Test
    @DisplayName("21 字符标签：400 + 明确文案")
    void shouldRejectTooLongTag() throws Exception {
        String longTag = "标".repeat(TaskTags.TAG_MAX_LENGTH + 1);
        HttpTestClient.Response response = http.post("/api/v1/tasks",
                "{\"title\":\"写周报\",\"tags\":[\"" + longTag + "\"]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("message").asString()).contains("不能超过 20");
    }

    @Test
    @DisplayName("按标签精确筛选：只返回含该标签的任务，total 正确")
    void shouldFilterByTag() throws Exception {
        create("写周报", "[\"工作\"]");
        create("读论文", "[\"学习\"]");
        create("买菜", "[\"生活\"]");
        create("写月报", "[\"工作\",\"月底\"]");

        JsonNode work = listTasks("?tag=" + URLEncoder.encode("工作", StandardCharsets.UTF_8));

        assertThat(work.path("total").asInt()).isEqualTo(2);
        assertThat(work.path("items").size()).isEqualTo(2);
        assertThat(work.path("items")).allSatisfy(item ->
                assertThat(tagList(item.path("tags"))).contains("工作"));
    }

    @Test
    @DisplayName("标签名不同但含相同子串：精确匹配，不做模糊")
    void shouldMatchTagExactly() throws Exception {
        create("写周报", "[\"工作\"]");
        create("写笔记", "[\"工作台\"]");

        JsonNode result = listTasks("?tag=" + URLEncoder.encode("工作", StandardCharsets.UTF_8));

        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("title").asString()).isEqualTo("写周报");
    }

    @Test
    @DisplayName("标签清单：去重且有序")
    void shouldListDistinctTags() throws Exception {
        create("写周报", "[\"工作\",\"本周\"]");
        create("写月报", "[\"工作\"]");
        create("读论文", "[\"学习\"]");

        HttpTestClient.Response response = http.get("/api/v1/tasks/tags");
        assertThat(response.status()).isEqualTo(200);
        List<String> tags = tagList(mapper.readTree(response.body()).path("data"));

        // 只断言「去重 + 内容完整」：具体顺序由数据库 collation 决定（中文既非拼音也非码位），
        // 把它写死会让测试在换 collation / 换机器时莫名其妙失败，而顺序稳定性由 ORDER BY 保证
        assertThat(tags).containsExactlyInAnyOrder("本周", "工作", "学习");
        assertThat(tags).doesNotHaveDuplicates();

        // 再查一次：顺序必须与上一次完全一致（这才是「有序」的可验证含义）
        List<String> again = tagList(mapper.readTree(http.get("/api/v1/tasks/tags").body()).path("data"));
        assertThat(again).containsExactlyElementsOf(tags);
    }

    @Test
    @DisplayName("没有标签时清单为空数组，不是 404")
    void shouldReturnEmptyTagList() throws Exception {
        create("写周报", null);

        HttpTestClient.Response response = http.get("/api/v1/tasks/tags");

        assertThat(response.status()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("data")).isEmpty();
    }

    @Test
    @DisplayName("删除任务：其独有标签从清单中消失（级联删除，无孤儿）")
    void shouldCascadeTagsOnDelete() throws Exception {
        create("写周报", "[\"工作\"]");
        JsonNode study = create("读论文", "[\"学习\"]");

        assertThat(http.delete("/api/v1/tasks/" + study.path("id").asString(), Map.of()).status())
                .isEqualTo(200);

        HttpTestClient.Response response = http.get("/api/v1/tasks/tags");
        List<String> tags = tagList(mapper.readTree(response.body()).path("data"));

        assertThat(tags).containsExactly("工作");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task_tag", Integer.class)).isEqualTo(1);
    }
}
