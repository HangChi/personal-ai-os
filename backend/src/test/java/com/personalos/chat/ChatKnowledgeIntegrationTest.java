package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.repository.ChatMessageCitationRepository;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.dto.KnowledgeHitDto.MatchedBy;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;

/**
 * 阶段 19：对话知识库增强的集成测试。
 *
 * <p>与 {@link ChatApiIntegrationTest} 同一套无网络 harness：桩 ChatModel + 真实
 * Testcontainers 数据库（Flyway V11 迁移因此也被真实验证）。
 * 检索侧用 {@code @Primary} 桩端口替换真实检索——要断言的是「编排与落库」，
 * 不是混合检索本身（那有专题评测，见 scripts/eval）。
 *
 * <p>核心时序断言（plan Complexity Tracking）：终帧回调里引用能挂到刚落库的
 * 助手消息上——依赖 MessageChatMemoryAdvisor 的 after() 位于订阅上游。
 * 若框架升级改变该顺序，{@link #citationsArePersistedAndReturnedByMessagesApi()} 会挂。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "app.ai.memory.max-messages=6"
})
@Testcontainers
class ChatKnowledgeIntegrationTest {

    private static final UUID DOC_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String HIT_A_CONTENT = "MVCC 靠 undo log 链实现-标记ZZZ";
    private static final String HIT_B_CONTENT = "RC 之下脏读可避免-标记YYY";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** 桩检索端口：固定两条命中；可注入故障；记录收到的 topK（验证收敛）。 */
    static class StubKnowledgeSearch implements KnowledgeSearchPort {

        private static final List<KnowledgeHitDto> HITS = List.of(
                new KnowledgeHitDto(DOC_A, "事务与隔离", "tx.md", 0, HIT_A_CONTENT, 0.91, MatchedBy.VECTOR),
                new KnowledgeHitDto(DOC_B, "隔离级别", "iso.md", 2, HIT_B_CONTENT, 0.83, MatchedBy.KEYWORD));

        private volatile boolean failNext;
        private volatile boolean returnEmpty;
        private volatile int lastTopK = -1;
        private volatile int lastQueryLength = -1;

        @Override
        public List<KnowledgeHitDto> search(String query, Integer topK) {
            lastTopK = topK == null ? -1 : topK;
            lastQueryLength = query == null ? -1 : query.length();
            if (failNext) {
                throw new RuntimeException("检索故障（桩）");
            }
            return returnEmpty ? List.of() : HITS;
        }

        void failNextCall() {
            this.failNext = true;
        }

        void returnEmptyNextCall() {
            this.returnEmpty = true;
        }

        void reset() {
            this.failNext = false;
            this.returnEmpty = false;
            this.lastTopK = -1;
            this.lastQueryLength = -1;
        }
    }

    /** 桩模型：与 ChatApiIntegrationTest 同一形态（两段式流式回复）。 */
    static class StubChatModel implements ChatModel {

        static final String FULL_REPLY = "已了解。";

        private final List<List<Message>> receivedInstructions = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(FULL_REPLY))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            return Flux.just("已了解", "。")
                    .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }

        List<Message> lastInstructions() {
            return receivedInstructions.get(receivedInstructions.size() - 1);
        }

        void reset() {
            receivedInstructions.clear();
        }
    }

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }

        /** @Primary：ObjectProvider.getIfAvailable() 取到的是桩而不是真实检索服务。 */
        @Bean
        @Primary
        StubKnowledgeSearch stubKnowledgeSearch() {
            return new StubKnowledgeSearch();
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int port;
    private final StubChatModel stub;
    private final StubKnowledgeSearch searchStub;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageCitationRepository citationRepository;

    @Autowired
    ChatKnowledgeIntegrationTest(@Value("${local.server.port}") int port,
                                 StubChatModel stub,
                                 StubKnowledgeSearch searchStub,
                                 ChatMessageRepository messageRepository,
                                 ChatMessageCitationRepository citationRepository) {
        this.port = port;
        this.stub = stub;
        this.searchStub = searchStub;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
    }

    @AfterEach
    void tearDown() {
        stub.reset();
        searchStub.reset();
    }

    // ------------------------------------------------------- 开关关闭：回归保护面

    @Test
    @DisplayName("useKnowledge 缺省/false：事件序列与阶段 1 一致，零检索零引用（FR-001）")
    void knowledgeOffKeepsLegacyBehavior() throws Exception {
        String conversationId = createSession();

        List<SseEvent> events = streamEvents(conversationId, "{\"content\":\"支持哪些隔离级别？\"}");

        assertThat(events).extracting(SseEvent::name)
                .containsExactly("start", "delta", "delta", "done");
        assertThat(searchStub.lastTopK).isEqualTo(-1);
        assertThat(citationCountOf(conversationId)).isZero();
    }

    // ------------------------------------------------------- 开关开启：事件 + 注入 + 落库

    @Test
    @DisplayName("增强开启：citations 事件先行，系统提示含编号片段，引用落库且可查回（FR-002/004/006）")
    void citationsArePersistedAndReturnedByMessagesApi() throws Exception {
        String conversationId = createSession();

        List<SseEvent> events = streamEvents(conversationId,
                "{\"content\":\"支持哪些隔离级别？\",\"useKnowledge\":true,\"topK\":4}");

        assertThat(events).extracting(SseEvent::name)
                .containsExactly("start", "citations", "delta", "delta", "done");
        SseEvent citationsEvent = events.get(1);
        assertThat(extractInt(citationsEvent.data(), "count")).isEqualTo(2);
        assertThat(citationsEvent.data()).contains(HIT_A_CONTENT).contains(HIT_B_CONTENT);
        assertThat(citationsEvent.data()).contains("\"documentTitle\":\"事务与隔离\"");
        assertThat(searchStub.lastTopK).isEqualTo(4);

        // 系统提示：基础人设之后追加编号片段与使用约束（C-01 格式）
        String systemText = stub.lastInstructions().stream()
                .filter(message -> message.getMessageType().name().equals("SYSTEM"))
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("[1] 文档《事务与隔离》（tx.md，第 1 段）");
        assertThat(systemText).contains("[2] 文档《隔离级别》（iso.md，第 3 段）");
        assertThat(systemText).contains(HIT_A_CONTENT);
        assertThat(systemText).contains("不得编造");
        assertThat(systemText).doesNotContain("支持哪些隔离级别？");

        // 终帧落库 + GET messages 带回（SC-002）
        String messages = get("/api/v1/chat/sessions/" + conversationId + "/messages").body();
        assertThat(messages).contains("\"citations\"");
        assertThat(messages).contains(HIT_A_CONTENT);
        assertThat(citationCountOf(conversationId)).isEqualTo(2);
    }

    @Test
    @DisplayName("增强开启但零命中：citations count=0 仍推送，正常回答，无引用落库")
    void zeroHitsStillAnswersWithoutCitations() throws Exception {
        String conversationId = createSession();
        searchStub.returnEmptyNextCall();

        List<SseEvent> events = streamEvents(conversationId,
                "{\"content\":\"知识库里没有的问题\",\"useKnowledge\":true}");

        assertThat(events).extracting(SseEvent::name)
                .containsExactly("start", "citations", "delta", "delta", "done");
        assertThat(extractInt(events.get(1).data(), "count")).isZero();
        assertThat(citationCountOf(conversationId)).isZero();
        // 零命中不注入检索区块：系统提示里没有编号片段
        String systemText = stub.lastInstructions().stream()
                .filter(message -> message.getMessageType().name().equals("SYSTEM"))
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).doesNotContain("[1]").doesNotContain("不得编造");
    }

    @Test
    @DisplayName("topK 越界收敛：99 → 20（FR-001）")
    void topKIsClamped() throws Exception {
        String conversationId = createSession();

        streamEvents(conversationId, "{\"content\":\"越界测试\",\"useKnowledge\":true,\"topK\":99}");

        assertThat(searchStub.lastTopK).isEqualTo(20);
    }

    // ------------------------------------------------------- 降级路径

    @Test
    @DisplayName("检索故障：error 降级提示 + 流继续答完 + 无引用落库（FR-005）")
    void retrievalFailureDegradesGracefully() throws Exception {
        String conversationId = createSession();
        searchStub.failNextCall();

        List<SseEvent> events = streamEvents(conversationId,
                "{\"content\":\"检索会失败的问题\",\"useKnowledge\":true}");

        assertThat(events).extracting(SseEvent::name)
                .contains("error", "delta", "delta", "done");
        SseEvent errorEvent = events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
        assertThat(errorEvent.data()).contains("知识库检索失败");
        // 降级后不注入检索区块，也不落引用
        assertThat(citationCountOf(conversationId)).isZero();
    }

    // ------------------------------------------------------- 级联删除

    @Test
    @DisplayName("删除会话 → 引用级联清空（FR-007）")
    void deletingSessionCascadesCitations() throws Exception {
        String conversationId = createSession();
        streamEvents(conversationId, "{\"content\":\"带引用的问题\",\"useKnowledge\":true}");
        assertThat(citationCountOf(conversationId)).isEqualTo(2);

        HttpResponse<String> deleted = delete("/api/v1/chat/sessions/" + conversationId);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(citationCountOf(conversationId)).isZero();
        assertThat(messageRepository.countByConversationId(conversationId)).isZero();
    }

    // ------------------------------------------------------- 工具方法

    /** 按会话计引用数（不用全局 count：同类用例共享容器库，全局数会被互相干扰）。 */
    private long citationCountOf(String conversationId) {
        List<Long> messageIds = messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId)
                .stream().map(message -> message.getId()).toList();
        return messageIds.isEmpty() ? 0 : citationRepository.countByMessageIdIn(messageIds);
    }

    private record SseEvent(String name, String data) {
    }

    private String createSession() throws Exception {
        String body = post("/api/v1/chat/sessions", "{}").body();
        String conversationId = extractString(body, "conversationId");
        assertThat(conversationId).as("创建会话失败: %s", body).isNotBlank();
        return conversationId;
    }

    private List<SseEvent> streamEvents(String conversationId, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/chat/sessions/" + conversationId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
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

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
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
