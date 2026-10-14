package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.support.HttpTestClient;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MCP 工具经**真实对话链路**的端到端验证（User Story 5）。
 *
 * <p>与 {@link McpConfirmationIntegrationTest} 的分工：那个测的是「工具回调本身」，
 * 这个测的是「工具从对话到达并执行」——桩模型（{@link ScriptedToolCallingChatModel}）
 * 编排一次工具调用，然后断言**工具执行结果回到了模型的下一轮上下文**。
 * 这比直接调回调强：它同时证明工具真的被装配进了 {@code ChatClient} 的 options
 * （否则 ToolCallingAdvisor 不会执行它，模型也拿不到结果）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.enabled=true",
        // 关掉真实模型：桩模型是唯一的 ChatModel，否则会与 ollamaChatModel 撞成两个候选
        "spring.ai.model.chat=none"
})
@Testcontainers
class McpChatToolCallingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final String JAVA = System.getProperty("java.home") + "/bin/java";

    /** 与确认测试用不同目录：两个测试类可能共享存活的上下文，路径隔离避免互相干扰 */
    private static final Path WORK_DIR = Path.of("target", "mcp-chat-it");
    private static final Path MARKER = WORK_DIR.resolve("closed.marker");
    private static final Path JOURNAL = WORK_DIR.resolve("journal.txt");

    @DynamicPropertySource
    static void mcpServerProperties(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(WORK_DIR);
        String classpath = System.getProperty("java.class.path");
        registry.add("mcp.servers.test.command", () -> JAVA);
        registry.add("mcp.servers.test.args", () -> String.join(",", List.of(
                "-cp", classpath, TestMcpServerMain.class.getName(),
                MARKER.toString(), JOURNAL.toString())));
        registry.add("mcp.servers.test.auto-tools", () -> "echo,ping");
    }

    @TestConfiguration
    static class StubModelConfiguration {
        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private final HttpTestClient http;
    private final ScriptedToolCallingChatModel model;
    private final ConfirmationRepository confirmationRepository;
    private final OperationRecordRepository operationRecordRepository;
    private final ConfirmationService confirmationService;

    @org.springframework.beans.factory.annotation.Autowired
    McpChatToolCallingIntegrationTest(@Value("${local.server.port}") int port,
                                      ScriptedToolCallingChatModel model,
                                      ConfirmationRepository confirmationRepository,
                                      OperationRecordRepository operationRecordRepository,
                                      ConfirmationService confirmationService) {
        this.http = new HttpTestClient(port);
        this.model = model;
        this.confirmationRepository = confirmationRepository;
        this.operationRecordRepository = operationRecordRepository;
        this.confirmationService = confirmationService;
    }

    @BeforeEach
    void cleanState() throws Exception {
        confirmationRepository.deleteAll();
        operationRecordRepository.deleteAll();
        Files.deleteIfExists(JOURNAL);
        Files.deleteIfExists(MARKER);
        model.reset();
    }

    @Test
    @DisplayName("AUTO 工具经对话链路：工具真的执行，结果回到模型下一轮上下文")
    void autoToolExecutesThroughChat() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("mcp_test_echo", "{\"text\":\"hi-from-chat\"}");
        model.enqueueText("已读取目录内容");

        List<HttpTestClient.SseEvent> events = stream(conversationId, "帮我 echo 一下");

        assertThat(events).extracting(HttpTestClient.SseEvent::name).contains("done");
        // 第二次模型调用的输入里必须带上工具执行结果——这才证明工具在对话链路里真的跑过
        assertThat(model.receivedInstructions()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(model.receivedInstructions().get(1).toString()).contains("echo:hi-from-chat");
    }

    @Test
    @DisplayName("CONFIRM 工具经对话链路：只生成待确认（零副作用），确认后才真正执行")
    void confirmToolThroughChatRequiresConfirmation() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("mcp_test_record", "{\"text\":\"from-chat\"}");
        model.enqueueText("这个操作需要你确认");

        stream(conversationId, "帮我把这句话记下来");

        // 拦截：server 未被触达
        assertThat(Files.exists(JOURNAL)).as("拦截阶段不得触达 server").isFalse();

        // 待确认记录：摘要含 server 与工具名，且绑定到本次会话
        var pending = confirmationService.list(conversationId, ConfirmationStatus.PENDING).stream()
                .filter(c -> "MCP_TOOL_CALL".equals(c.action()))
                .findFirst()
                .orElseThrow();
        assertThat(pending.summary()).contains("server=test").contains("工具=record");

        // 确认 → 真正执行
        var execution = confirmationService.confirm(pending.id(), pending.token());
        assertThat(execution.executed()).isTrue();
        assertThat(Files.readString(JOURNAL).trim()).isEqualTo("recorded:from-chat");
    }

    private String createSession() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/chat/sessions", "{}");
        assertThat(response.status()).isEqualTo(201);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"conversationId\"\\s*:\\s*\"([^\"]*)\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private List<HttpTestClient.SseEvent> stream(String conversationId, String content) throws Exception {
        return http.sse("/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"" + content + "\"}", Map.of());
    }
}
