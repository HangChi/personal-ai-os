package com.personalos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.AiProperties;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.repository.MemoryRepository;
import com.personalos.support.HttpTestClient;
import com.personalos.support.Instants;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 长期记忆集成测试（真实 HTTP + 真实 PostgreSQL + 脚本化模型）。
 *
 * <h2>为什么注入断言看 SystemMessage 而不是模型回答</h2>
 * 模型回答是"它想让我们看到的结果"。记忆是否真的进了上下文，只有看送给模型的
 * 消息才能确定 —— 这也是唯一能验证 SC-001 / SC-004 / SC-006 的层次。
 *
 * <h2>为什么注入用例要走真实对话</h2>
 * 直接调 {@code MemoryService.renderForPrompt()} 只能证明「渲染得对」，
 * 证明不了「真的被拼进了请求」—— 后者依赖 {@code ChatCompletionService} 的装配，
 * 而装配错误（例如请求级 system 覆盖掉基础人设）不会报错，只会静默出错。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none"
})
@Testcontainers
class MemoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private static final String MEMORY_API = "/api/v1/memories";

    private final HttpTestClient http;
    private final MemoryRepository memoryRepository;
    private final ScriptedToolCallingChatModel model;
    private final AiProperties aiProperties;

    @Autowired
    MemoryIntegrationTest(@Value("${local.server.port}") int port,
                          MemoryRepository memoryRepository,
                          ScriptedToolCallingChatModel model,
                          AiProperties aiProperties) {
        this.http = new HttpTestClient(port);
        this.memoryRepository = memoryRepository;
        this.model = model;
        this.aiProperties = aiProperties;
    }

    @BeforeEach
    void cleanUp() {
        memoryRepository.deleteAll();
        model.reset();
    }

    // ------------------------------------------------------------------ REST

    @Test
    @DisplayName("空库列表返回空数组，而不是 404 或 null")
    void listIsEmptyArrayWhenNoMemory() throws Exception {
        HttpTestClient.Response response = http.get(MEMORY_API);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"data\":[]");
    }

    @Test
    @DisplayName("新增记忆：内容去首尾空白后落库")
    void createTrimsContent() throws Exception {
        HttpTestClient.Response response = http.post(MEMORY_API, "{\"content\":\"  周报每周五下午交  \"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(extractString(response.body(), "content")).isEqualTo("周报每周五下午交");
        assertThat(memoryRepository.findAll()).hasSize(1);
        assertThat(memoryRepository.findAll().get(0).getContent()).isEqualTo("周报每周五下午交");
    }

    @Test
    @DisplayName("重复提交同一内容：不新增行，返回同一条（幂等）")
    void duplicateContentIsIdempotent() throws Exception {
        String first = extractString(http.post(MEMORY_API, "{\"content\":\"我常住扬州\"}").body(), "id");
        String second = extractString(http.post(MEMORY_API, "{\"content\":\"  我常住扬州  \"}").body(), "id");
        String third = extractString(http.post(MEMORY_API, "{\"content\":\"我常住扬州\"}").body(), "id");

        assertThat(first).isNotBlank();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(memoryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("空内容四态 → 400，且表零写入")
    void blankContentIsRejected() throws Exception {
        for (String body : List.of("{\"content\":\"\"}", "{\"content\":\"   \"}", "{}", "{\"content\":null}")) {
            HttpTestClient.Response response = http.post(MEMORY_API, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body()).as(body).contains("\"code\":40000");
        }
        assertThat(memoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("长度上限：2000 通过、2001 拒绝（按去空白后计算）")
    void contentLengthLimit() throws Exception {
        HttpTestClient.Response tooLong = http.post(MEMORY_API,
                "{\"content\":\"" + "x".repeat(2001) + "\"}");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"code\":40000");

        HttpTestClient.Response exact = http.post(MEMORY_API,
                "{\"content\":\"" + "x".repeat(2000) + "\"}");
        assertThat(exact.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("编辑：内容更新、created_at 不变、updated_at 刷新")
    void updateKeepsCreatedAt() throws Exception {
        String created = http.post(MEMORY_API, "{\"content\":\"周报每周五下午交\"}").body();
        String id = extractString(created, "id");
        String createdAt = extractString(created, "createdAt");
        String updatedAtBefore = extractString(created, "updatedAt");

        Thread.sleep(5); // 让 updated_at 有可观测差异（毫秒精度下 5ms 足够）
        HttpTestClient.Response updated = http.patch(MEMORY_API + "/" + id,
                "{\"content\":\"周报每周五上午交\"}", Map.of());

        assertThat(updated.status()).isEqualTo(200);
        assertThat(extractString(updated.body(), "content")).isEqualTo("周报每周五上午交");
        // createdAt 由 POST 的**内存实体**序列化而来（JVM 时钟，Linux 上带纳秒），
        // PATCH 时是从库读回的（TIMESTAMPTZ 只到微秒）—— 直接比字符串在 CI 上必然不等。
        assertThat(Instants.sameTextAtStoragePrecision(extractString(updated.body(), "createdAt"), createdAt))
                .as("created_at 不应被编辑改动（按存储精度比较）")
                .isTrue();
        assertThat(extractString(updated.body(), "updatedAt")).isNotEqualTo(updatedAtBefore);
    }

    @Test
    @DisplayName("删除：200 后表为空；再删同一条 → 404")
    void deleteThenMissing() throws Exception {
        String id = extractString(http.post(MEMORY_API, "{\"content\":\"要删掉的记忆\"}").body(), "id");

        assertThat(http.delete(MEMORY_API + "/" + id, Map.of()).status()).isEqualTo(200);
        assertThat(memoryRepository.count()).isZero();

        HttpTestClient.Response again = http.delete(MEMORY_API + "/" + id, Map.of());
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.body()).contains("\"code\":40004");
    }

    @Test
    @DisplayName("非法标识 → 400（不进入业务逻辑）；合法但不存在 → 404")
    void invalidAndMissingId() throws Exception {
        HttpTestClient.Response invalid = http.patch(MEMORY_API + "/bad_id_x", "{\"content\":\"随便\"}", Map.of());
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"code\":40000");

        HttpTestClient.Response missing = http.patch(MEMORY_API + "/" + UUID.randomUUID(),
                "{\"content\":\"随便\"}", Map.of());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":40004");
    }

    // ------------------------------------------------------------------ 注入

    @Test
    @DisplayName("有记忆时：送给模型的系统提示包含基础人设与记忆块")
    void memoryIsInjectedIntoSystemPrompt() throws Exception {
        memoryRepository.save(new MemoryEntity("周报每周五下午交", Instant.now()));
        model.enqueueText("收到");

        chatOnce("你好");

        String system = systemPromptOfLastCall();
        assertThat(system).contains(aiProperties.systemPrompt());
        assertThat(system).contains("## 长期记忆");
        assertThat(system).contains("周报每周五下午交");
    }

    @Test
    @DisplayName("无记忆时：系统提示与阶段 1 逐字符相同（不出现空标题或空行）")
    void noMemoryKeepsSystemPromptUnchanged() throws Exception {
        model.enqueueText("收到");

        chatOnce("你好");

        assertThat(systemPromptOfLastCall()).isEqualTo(aiProperties.systemPrompt());
    }

    @Test
    @DisplayName("注入条数上限：35 条只注入 30 条，且为最近更新的 30 条")
    void injectionCapsAtThirty() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<MemoryEntity> memories = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            // updated_at 递减：第 1 条最新
            memories.add(new MemoryEntity("记忆编号" + i, base.minusSeconds(i)));
        }
        memoryRepository.saveAll(memories);
        model.enqueueText("收到");

        chatOnce("你好");

        String system = systemPromptOfLastCall();
        assertThat(system).contains("1. 记忆编号1");
        assertThat(system).contains("30. 记忆编号30");
        assertThat(system).doesNotContain("记忆编号31");
    }

    @Test
    @DisplayName("删除后不再注入（无需重启、无缓存）")
    void deletedMemoryIsNotInjected() throws Exception {
        String id = extractString(http.post(MEMORY_API, "{\"content\":\"过时的偏好\"}").body(), "id");
        http.delete(MEMORY_API + "/" + id, Map.of());
        model.enqueueText("收到");

        chatOnce("你好");

        assertThat(systemPromptOfLastCall()).doesNotContain("过时的偏好");
        assertThat(systemPromptOfLastCall()).doesNotContain("## 长期记忆");
    }

    // ------------------------------------------------------------------ Agent 工具

    @Test
    @DisplayName("工具 saveMemory：模型调用后落库，并把「已记住」回给模型")
    void toolSavesMemory() throws Exception {
        model.enqueueToolCall("saveMemory", "{\"content\":\"我的周报每周五下午交\"}");
        model.enqueueText("好的，已经记住了。");

        chatOnce("记住：我的周报每周五下午交");

        assertThat(memoryRepository.count()).isEqualTo(1);
        assertThat(memoryRepository.findAll().get(0).getContent()).isEqualTo("我的周报每周五下午交");
        // 工具结果是否回灌给模型：断言存在 TOOL 类型消息，而不是去读 ToolResponseMessage 的
        // getText()（那是框架内部字段组织方式，换个版本就变；消息类型是稳定的契约）
        boolean toolResultFedBack = model.receivedInstructions().stream()
                .flatMap(List::stream)
                .anyMatch(message -> message.getMessageType() == MessageType.TOOL);
        assertThat(toolResultFedBack).as("工具执行结果应作为 TOOL 消息回灌给模型").isTrue();
    }

    // ------------------------------------------------------------------ 辅助

    /** 走一遍真实对话：创建会话 → 流式一轮 */
    private void chatOnce(String content) throws Exception {
        String sessionBody = http.post("/api/v1/chat/sessions", "{}").body();
        String conversationId = extractString(sessionBody, "conversationId");
        assertThat(conversationId).as("创建会话失败: %s", sessionBody).isNotBlank();

        List<HttpTestClient.SseEvent> events = http.sse(
                "/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"" + content + "\"}", Map.of());
        assertThat(events).extracting(HttpTestClient.SseEvent::name).contains("done");
    }

    /** 最后一次模型调用收到的系统提示 */
    private String systemPromptOfLastCall() {
        List<List<Message>> calls = model.receivedInstructions();
        assertThat(calls).as("模型应至少被调用一次").isNotEmpty();
        return calls.get(calls.size() - 1).stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElse("");
    }

    private static String extractString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
