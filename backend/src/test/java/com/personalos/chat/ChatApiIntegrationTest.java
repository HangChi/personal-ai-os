package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;

/**
 * 阶段 1 聊天功能的集成测试。
 *
 * <p><b>全程无网络、无 API Key</b>：通过 {@code spring.ai.model.chat=none}
 * 关闭真实模型自动配置，再注入一个桩 {@link ChatModel}。
 * 桩模型记录「收到了哪些消息」，因此可以精确验证多轮上下文与会话隔离 ——
 * 这是连真实模型也做不到的（只能看输出猜输入）。
 *
 * <p>数据库使用 Testcontainers 拉起与 {@code compose.yml} 相同的镜像，
 * 因此 Flyway 迁移（含 V2 建表）也被真实验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "app.ai.memory.max-messages=6"
})
@Testcontainers
class ChatApiIntegrationTest {

    private static final String USER_TURN_1 = "第一轮问题-标记AAAA";
    private static final String USER_TURN_3 = "第三轮问题-标记CCCC";
    private static final String CURRENT_TURN = "第5轮问题";
    private static final String SECRET_A = "暗号A-仅属于会话A-9f3b";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** 桩模型：返回固定文本，并记录每次调用收到的指令（即送给模型的上下文） */
    static class StubChatModel implements ChatModel {

        static final String CHUNK_1 = "好的，";
        static final String CHUNK_2 = "我明白了。";
        static final String FULL_REPLY = CHUNK_1 + CHUNK_2;

        private final List<List<Message>> receivedInstructions = new CopyOnWriteArrayList<>();
        private volatile RuntimeException pendingFailure;

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            if (pendingFailure != null) {
                throw pendingFailure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(FULL_REPLY))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            if (pendingFailure != null) {
                return Flux.error(pendingFailure);
            }
            return Flux.just(CHUNK_1, CHUNK_2)
                    .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }

        void failNextCallWith(RuntimeException failure) {
            this.pendingFailure = failure;
        }

        List<List<Message>> receivedInstructions() {
            return receivedInstructions;
        }

        List<Message> lastInstructions() {
            return receivedInstructions.get(receivedInstructions.size() - 1);
        }

        void reset() {
            receivedInstructions.clear();
            pendingFailure = null;
        }
    }

    @TestConfiguration
    static class StubChatModelConfiguration {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int port;
    private final StubChatModel stub;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;

    @Autowired
    ChatApiIntegrationTest(@Value("${local.server.port}") int port,
                           StubChatModel stub,
                           ChatMessageRepository messageRepository,
                           ChatSessionRepository sessionRepository) {
        this.port = port;
        this.stub = stub;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    // ---------------------------------------------------------------- 会话管理

    @Test
    @DisplayName("创建会话返回 201 与统一信封；随后出现在会话列表中")
    void createSessionThenAppearsInList() throws Exception {
        HttpResponse<String> created = post("/api/v1/chat/sessions", "{\"title\":\"集成测试会话\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(extractInt(created.body(), "code")).isZero();
        String conversationId = extractString(created.body(), "conversationId");
        assertThat(conversationId).isNotBlank();
        assertThat(created.body()).doesNotContain("\"success\"");

        String list = get("/api/v1/chat/sessions").body();
        assertThat(list).contains(conversationId).contains("集成测试会话");
    }

    @Test
    @DisplayName("不存在的会话查消息 → 404 且为统一错误结构（不静默返回空数组）")
    void unknownSessionReturns404() throws Exception {
        HttpResponse<String> response = get("/api/v1/chat/sessions/does-not-exist-0001/messages");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":40004");
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("java.");
    }

    @Test
    @DisplayName("非法会话标识 → 400，且不进入业务逻辑")
    void invalidConversationIdReturns400() throws Exception {
        HttpResponse<String> response = get("/api/v1/chat/sessions/bad_id_has_underscore/messages");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
    }

    @Test
    @DisplayName("空消息内容 → 400 校验失败，且不建立 SSE 流")
    void blankContentIsRejectedBeforeStreaming() throws Exception {
        String conversationId = createSession();

        HttpResponse<String> response = post("/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"   \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
    }

    @Test
    @DisplayName("删除会话 → 消息级联删除，列表不再返回它")
    void deleteSessionCascadesMessages() throws Exception {
        String conversationId = createSession();
        streamOnce(conversationId, "会被一起删掉的消息");
        assertThat(messageRepository.countByConversationId(conversationId)).isEqualTo(2);

        HttpResponse<String> deleted = delete("/api/v1/chat/sessions/" + conversationId);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(messageRepository.countByConversationId(conversationId)).isZero();
        assertThat(get("/api/v1/chat/sessions").body()).doesNotContain(conversationId);
        assertThat(get("/api/v1/chat/sessions/" + conversationId + "/messages").statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- 流式对话

    @Test
    @DisplayName("流式对话：start → delta×2 → done，且 done 的完整文本等于各 delta 拼接（SC-002）")
    void streamEmitsStartDeltaDoneAndPersistsBothMessages() throws Exception {
        String conversationId = createSession();

        List<SseEvent> events = streamEvents(conversationId, "用一句话介绍你自己");

        assertThat(events).extracting(SseEvent::name)
                .containsExactly("start", "delta", "delta", "done");
        assertThat(events.get(0).data()).contains(conversationId);

        String concatenated = events.stream()
                .filter(event -> "delta".equals(event.name()))
                .map(event -> extractString(event.data(), "content"))
                .reduce("", String::concat);
        String doneContent = extractString(events.get(3).data(), "content");

        assertThat(concatenated).isEqualTo(StubChatModel.FULL_REPLY);
        assertThat(doneContent).isEqualTo(concatenated);

        // 一轮对话恰好落库 2 条（1 用户 + 1 助手）
        List<ChatMessageEntity> stored = messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(stored.get(1).getMessageType().name()).isEqualTo("ASSISTANT");
        assertThat(stored.get(1).getContent()).isEqualTo(StubChatModel.FULL_REPLY);
        assertThat(stored.get(0).getSequenceNo()).isEqualTo(1);
        assertThat(stored.get(1).getSequenceNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("首条消息自动生成标题（默认标题被替换）")
    void firstMessageDerivesTitle() throws Exception {
        String conversationId = createSession();
        streamOnce(conversationId, "帮我制定三天的学习计划");

        String list = get("/api/v1/chat/sessions").body();
        Matcher matcher = Pattern.compile("\"conversationId\":\"" + Pattern.quote(conversationId)
                + "\",\"title\":\"([^\"]*)\"").matcher(list);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("帮我制定三天的学习计划");
    }

    @Test
    @DisplayName("模型侧异常 → 推送 error 事件而非静默断开（SC-006）")
    void upstreamFailureEmitsErrorEvent() throws Exception {
        String conversationId = createSession();
        // 真实场景里 Spring AI 会把上游连接错误包在运行时异常中抛出，
        // 这里刻意复现这种「受检异常藏在 cause 链里」的形态，
        // 以验证 classify() 确实会沿 cause 链识别上游故障。
        stub.failNextCallWith(new IllegalStateException("模型调用失败",
                new java.io.IOException("connection refused (stub)")));

        List<SseEvent> events = streamEvents(conversationId, "这会失败");

        assertThat(events).extracting(SseEvent::name).contains("error");
        SseEvent errorEvent = events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
        // 上游失败映射为 50200，且文案里不含堆栈/类名
        assertThat(extractInt(errorEvent.data(), "code")).isEqualTo(50200);
        assertThat(errorEvent.data()).doesNotContain("IOException").doesNotContain("java.");
    }

    // ---------------------------------------------------------------- 多轮上下文

    @Test
    @DisplayName("第二轮请求送给模型的上下文包含第一轮内容（FR-009）")
    void secondTurnReceivesFirstTurnContext() throws Exception {
        String conversationId = createSession();

        streamOnce(conversationId, USER_TURN_1);
        streamOnce(conversationId, "第二轮问题");

        List<Message> secondTurnInstructions = stub.lastInstructions();
        String joined = secondTurnInstructions.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains(USER_TURN_1).contains(StubChatModel.FULL_REPLY);
        assertThat(messageRepository.countByConversationId(conversationId)).isEqualTo(4);
    }

    @Test
    @DisplayName("上下文窗口生效，但库中历史不被裁剪（SC-004 / FR-007）")
    void contextIsWindowedWhileHistoryIsPreserved() throws Exception {
        String conversationId = createSession();

        for (int turn = 1; turn <= 5; turn++) {
            String content = switch (turn) {
                case 1 -> USER_TURN_1;
                case 3 -> USER_TURN_3;
                default -> "第" + turn + "轮问题";
            };
            streamOnce(conversationId, content);
        }

        // 历史完整：5 轮 × 2 条 = 10 条，一条都没被窗口裁掉
        assertThat(messageRepository.countByConversationId(conversationId)).isEqualTo(10);

        // 上下文被窗口限制：只约束**历史部分**（≤ max-messages = 6）。
        // 实测 MessageChatMemoryAdvisor 的行为：先用 get() 取窗口内的历史，
        // 再把「当前用户消息」与「system 提示」追加进去，
        // 因此总条数 = 历史(≤6) + 当前用户消息(1) + system(1)。
        List<Message> lastInstructions = stub.lastInstructions();
        long historyInContext = lastInstructions.stream()
                .filter(message -> message.getMessageType() != MessageType.SYSTEM)
                .filter(message -> !CURRENT_TURN.equals(message.getText()))
                .count();
        assertThat(historyInContext)
                .as("送入模型的历史条数应被 max-messages 限制")
                .isLessThanOrEqualTo(6);

        String joined = lastInstructions.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).doesNotContain(USER_TURN_1);   // 最早的已被窗口滑出
        assertThat(joined).contains(USER_TURN_3);         // 较新的仍在上下文里
        assertThat(joined).contains(CURRENT_TURN);        // 本轮消息当然在
    }

    // ---------------------------------------------------------------- 会话隔离

    @Test
    @DisplayName("会话隔离：B 会话的上下文不含 A 会话的任何内容（SC-003）")
    void conversationsAreIsolated() throws Exception {
        String sessionA = createSession();
        String sessionB = createSession();

        streamOnce(sessionA, SECRET_A);
        streamOnce(sessionB, "这是会话B的第一个问题");

        String joinedForB = stub.lastInstructions().stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(joinedForB)
                .doesNotContain(SECRET_A)
                .doesNotContain(StubChatModel.FULL_REPLY + "\n" + StubChatModel.FULL_REPLY);
        assertThat(messageRepository.countByConversationId(sessionA)).isEqualTo(2);
        assertThat(messageRepository.countByConversationId(sessionB)).isEqualTo(2);
    }

    @Test
    @DisplayName("删除会话 A 不影响会话 B")
    void deletingOneConversationDoesNotAffectAnother() throws Exception {
        String sessionA = createSession();
        String sessionB = createSession();
        streamOnce(sessionA, "A的消息");
        streamOnce(sessionB, "B的消息");

        delete("/api/v1/chat/sessions/" + sessionA);

        assertThat(get("/api/v1/chat/sessions/" + sessionB + "/messages").body())
                .contains("B的消息")
                .contains("ASSISTANT");
        assertThat(messageRepository.countByConversationId(sessionB)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 工具方法

    private record SseEvent(String name, String data) {
    }

    private String createSession() throws Exception {
        String body = post("/api/v1/chat/sessions", "{}").body();
        String conversationId = extractString(body, "conversationId");
        assertThat(conversationId).as("创建会话失败: %s", body).isNotBlank();
        return conversationId;
    }

    private void streamOnce(String conversationId, String content) throws Exception {
        List<SseEvent> events = streamEvents(conversationId, content);
        assertThat(events).extracting(SseEvent::name).contains("done");
    }

    private List<SseEvent> streamEvents(String conversationId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/chat/sessions/" + conversationId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + content + "\"}"))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).as("SSE 状态码应为 200").isEqualTo(200);

        List<SseEvent> events = new ArrayList<>();
        String currentEvent = null;
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    events.add(new SseEvent(currentEvent, line.substring("data:".length()).trim()));
                }
            }
        }
        return events;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------- 会话重命名（阶段 7）

    @Test
    @DisplayName("重命名 → 200，返回完整会话视图；列表随后显示新标题")
    void renameSessionUpdatesTitle() throws Exception {
        String conversationId = createSession();

        HttpResponse<String> response = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"报销流程问答\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(extractInt(response.body(), "code")).isZero();
        assertThat(extractString(response.body(), "title")).isEqualTo("报销流程问答");
        // 返回完整视图：前端据此就地替换列表项，无需二次请求（FR-008）
        assertThat(response.body()).contains("messageCount").contains("updatedAt");

        assertThat(get("/api/v1/chat/sessions").body()).contains("报销流程问答");
    }

    @Test
    @DisplayName("重命名幂等：连续两次相同标题都成功且结果一致")
    void renameSessionIsIdempotent() throws Exception {
        String conversationId = createSession();

        HttpResponse<String> first = patch("/api/v1/chat/sessions/" + conversationId, "{\"title\":\"同一个名字\"}");
        HttpResponse<String> second = patch("/api/v1/chat/sessions/" + conversationId, "{\"title\":\"同一个名字\"}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(extractString(second.body(), "title")).isEqualTo("同一个名字");
    }

    @Test
    @DisplayName("重命名会去除首尾空白")
    void renameSessionTrimsTitle() throws Exception {
        String conversationId = createSession();

        HttpResponse<String> response = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"   报销流程   \"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(extractString(response.body(), "title")).isEqualTo("报销流程");
        assertThat(sessionRepository.findById(conversationId).orElseThrow().getTitle())
                .isEqualTo("报销流程");
    }

    @Test
    @DisplayName("空标题四态（空串/空白/无字段/null）→ 400，且库中标题不变")
    void renameSessionRejectsBlankTitle() throws Exception {
        String conversationId = createSession();

        for (String body : List.of("{\"title\":\"\"}", "{\"title\":\"   \"}", "{}", "{\"title\":null}")) {
            HttpResponse<String> response = patch("/api/v1/chat/sessions/" + conversationId, body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(response.body()).as(body).contains("\"code\":40000");
        }

        assertThat(sessionRepository.findById(conversationId).orElseThrow().getTitle())
                .isEqualTo("新会话");
    }

    @Test
    @DisplayName("201 字 → 400；恰好 200 字 → 200")
    void renameSessionEnforcesLengthLimit() throws Exception {
        String conversationId = createSession();

        HttpResponse<String> tooLong = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"" + "x".repeat(201) + "\"}");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"code\":40000");

        HttpResponse<String> exact = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"" + "x".repeat(200) + "\"}");
        assertThat(exact.statusCode()).isEqualTo(200);
        assertThat(extractString(exact.body(), "title")).hasSize(200);
    }

    @Test
    @DisplayName("长度按去空白后计算：原始 230 字、归一后 190 字 → 200")
    void renameSessionMeasuresLengthAfterTrim() throws Exception {
        String conversationId = createSession();
        String padded = "  " + "x".repeat(190) + "  ".repeat(19);

        HttpResponse<String> response = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"" + padded + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sessionRepository.findById(conversationId).orElseThrow().getTitle()).hasSize(190);
    }

    @Test
    @DisplayName("重命名不存在的会话 → 404（不静默创建）")
    void renameUnknownSessionReturns404() throws Exception {
        HttpResponse<String> response = patch("/api/v1/chat/sessions/does-not-exist-0001",
                "{\"title\":\"随便\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":40004");
    }

    @Test
    @DisplayName("非法会话标识 → 400")
    void renameInvalidIdReturns400() throws Exception {
        HttpResponse<String> response = patch("/api/v1/chat/sessions/bad_id_has_underscore",
                "{\"title\":\"随便\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
    }

    @Test
    @DisplayName("重命名不改排序：改最旧会话后列表仍按活动时间倒序，updatedAt 原样保留")
    void renameSessionKeepsListOrder() throws Exception {
        // 直接建三个 updatedAt 明确递减的会话。走 API 创建的话时间戳可能落在同一毫秒，
        // 并列时 ORDER BY 的返回顺序不确定 —— 那样的断言在测数据库的稳定性，不是测我们的行为。
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        ChatSessionEntity oldest = sessionRepository.save(
                new ChatSessionEntity("order-a-0001", "最旧", base, base));
        ChatSessionEntity middle = sessionRepository.save(
                new ChatSessionEntity("order-b-0002", "中间", base, base.plusSeconds(60)));
        ChatSessionEntity newest = sessionRepository.save(
                new ChatSessionEntity("order-c-0003", "最新", base, base.plusSeconds(120)));

        HttpResponse<String> response = patch("/api/v1/chat/sessions/" + oldest.getConversationId(),
                "{\"title\":\"改了名的最旧\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        // 关键：updated_at 必须原样保留（FR-004）
        assertThat(sessionRepository.findById(oldest.getConversationId()).orElseThrow().getUpdatedAt())
                .isEqualTo(base);

        String list = get("/api/v1/chat/sessions").body();
        int newestIndex = list.indexOf(newest.getConversationId());
        int middleIndex = list.indexOf(middle.getConversationId());
        int oldestIndex = list.indexOf(oldest.getConversationId());

        assertThat(newestIndex).isGreaterThanOrEqualTo(0);
        assertThat(newestIndex).isLessThan(middleIndex);
        assertThat(middleIndex).isLessThan(oldestIndex);
    }

    @Test
    @DisplayName("重命名不动消息：消息条数与内容均不变")
    void renameSessionDoesNotTouchMessages() throws Exception {
        String conversationId = createSession();
        streamOnce(conversationId, "这条消息不该被改名影响");
        long before = messageRepository.countByConversationId(conversationId);

        HttpResponse<String> response = patch("/api/v1/chat/sessions/" + conversationId,
                "{\"title\":\"改名后的会话\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(messageRepository.countByConversationId(conversationId)).isEqualTo(before);
        assertThat(get("/api/v1/chat/sessions/" + conversationId + "/messages").body())
                .contains("这条消息不该被改名影响");
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String jsonBody) throws Exception {
        // Java 的 HttpRequest.Builder 没有 patch() 快捷方法，只能走 method("PATCH", ...)
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String extractString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int extractInt(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MIN_VALUE;
    }
}
