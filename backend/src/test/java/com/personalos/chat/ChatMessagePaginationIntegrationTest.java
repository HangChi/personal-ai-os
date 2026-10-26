package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.entity.MessageRole;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 消息分页（阶段 21）的集成测试。
 *
 * <p>只验证「读取」这一条路径，因此不需要桩模型：数据直接经仓储写入，
 * 顺序号完全可控。这样断言可以精确到「第几条到第几条」，
 * 而不是从一次真实对话里反推顺序。
 *
 * <p>核心不变量有两条：
 * <ol>
 *   <li><b>不带参数 = 老行为</b>：返回全部消息。导出与既有客户端依赖它，
 *       分页必须是可选增强，而不是契约变更。</li>
 *   <li><b>返回永远是正序</b>：仓储按顺序号倒序取（为了拿「最近的 N 条」），
 *       反转发生在服务层。若忘记反转，前端会出现「新消息在最上面」。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "app.calendar.scan-enabled=false"
})
@Testcontainers
class ChatMessagePaginationIntegrationTest {

    private static final String CONVERSATION_ID = "paging-test-conversation";
    /** 造 7 条消息：sequence_no 1..7 */
    private static final int TOTAL_MESSAGES = 7;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int port;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    ChatMessagePaginationIntegrationTest(@Value("${local.server.port}") int port,
                                         ChatMessageRepository messageRepository,
                                         ChatSessionRepository sessionRepository,
                                         PlatformTransactionManager transactionManager) {
        this.port = port;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        // 用显式事务写入并**提交**：这些数据要经 HTTP 由另一个连接读回，
        // 若用测试级 @Transactional 包裹，写入不会提交，接口会查不到。
        transactionTemplate.executeWithoutResult(status -> {
            messageRepository.deleteByConversationId(CONVERSATION_ID);
            sessionRepository.deleteById(CONVERSATION_ID);
            Instant now = Instant.parse("2026-10-26T00:00:00Z");
            sessionRepository.save(new ChatSessionEntity(CONVERSATION_ID, "分页测试会话", now, now));
            List<ChatMessageEntity> messages = new ArrayList<>();
            for (int sequenceNo = 1; sequenceNo <= TOTAL_MESSAGES; sequenceNo++) {
                // 用户与助手交替，贴近真实会话形状
                MessageRole role = sequenceNo % 2 == 1 ? MessageRole.USER : MessageRole.ASSISTANT;
                messages.add(new ChatMessageEntity(CONVERSATION_ID, role, "第" + sequenceNo + "条", sequenceNo, now));
            }
            messageRepository.saveAll(messages);
        });
    }

    @Test
    @DisplayName("不带 limit 返回全部消息 —— 分页是可选增强，不是契约变更")
    void withoutLimitReturnsEverything() throws Exception {
        HttpResponse<String> response = get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sequenceNos(response.body())).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    @DisplayName("limit 取的是**最新**若干条，且仍按顺序号正序返回")
    void limitReturnsNewestSliceInAscendingOrder() throws Exception {
        HttpResponse<String> response = get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=3");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sequenceNos(response.body()))
                .as("取最新 3 条（5,6,7），且正序 —— 反转漏做会变成 7,6,5")
                .containsExactly(5, 6, 7);
    }

    @Test
    @DisplayName("beforeSeq 以顺序号为游标向前翻，与上一页不重不漏")
    void beforeSeqPagesBackwards() throws Exception {
        HttpResponse<String> response =
                get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=3&beforeSeq=5");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sequenceNos(response.body()))
                .as("从 5 往前取 3 条：4,3,2（正序即 2,3,4；5 本身不包含）")
                .containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("翻到会话开头时返回剩余条数，不会报错也不会补空位")
    void beforeSeqAtHeadReturnsRemainder() throws Exception {
        HttpResponse<String> response =
                get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=3&beforeSeq=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sequenceNos(response.body())).containsExactly(1);
    }

    @Test
    @DisplayName("limit 越界 → 400，而不是静默截断成合法值")
    void invalidLimitIsRejected() throws Exception {
        assertThat(get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=0").statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=201").statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/chat/sessions/" + CONVERSATION_ID + "/messages?limit=3&beforeSeq=0").statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("分页参数不影响「会话不存在」的 404")
    void unknownSessionStillReturns404() throws Exception {
        HttpResponse<String> response = get("/api/v1/chat/sessions/no-such-session/messages?limit=10");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":40004");
    }

    // ---------------------------------------------------------------- 辅助

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 抽取响应体中的 sequenceNo 序列（保持出现顺序，这是断言的另一半） */
    private static List<Integer> sequenceNos(String json) {
        List<Integer> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"sequenceNo\"\\s*:\\s*(\\d+)").matcher(json);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return result;
    }
}
