package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.client.McpServerStatus;
import com.personalos.mcp.config.McpToolConflictValidator;
import com.personalos.mcp.tool.McpToolCallbackProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MCP 的启动行为边界：坏 server 降级、以及**没有模型时 MCP 组件照样可用**。
 *
 * <p>两类故障刻意区别对待（research.md R-07）：配置错误 → 启动失败
 * （由 {@code McpClientRegistryIntegrationTest} 的校验用例覆盖）；
 * 单个 server 连接失败 → 降级，应用照常启动（本类覆盖）。
 *
 * <p>无模型场景守住既有承诺（SC-03 的邻居）：未配置任何 ChatModel 时，
 * MCP 客户端仍应初始化、工具仍应可列举——只是没有对话能用它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.enabled=true",
                "mcp.servers.broken.command=/nonexistent/mcp-server",
                // 关掉全部真实模型：模拟「没装 Ollama、也没配 API Key」
                "spring.ai.model.chat=none",
        })
@Testcontainers
class McpDegradedStartupIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final String JAVA = System.getProperty("java.home") + "/bin/java";
    private static final Path WORK_DIR = Path.of("target", "mcp-degraded-it");

    @DynamicPropertySource
    static void workingServerProperties(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(WORK_DIR);
        String classpath = System.getProperty("java.class.path");
        registry.add("mcp.servers.test.command", () -> JAVA);
        registry.add("mcp.servers.test.args", () -> String.join(",", List.of(
                "-cp", classpath, TestMcpServerMain.class.getName(),
                WORK_DIR.resolve("closed.marker").toString(),
                WORK_DIR.resolve("journal.txt").toString())));
    }

    private final ApplicationContext context;

    @Autowired
    McpDegradedStartupIntegrationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("坏 server 不拖垮启动：上下文就绪，注册表在册，该 server 标记不可用，另一个照常可用")
    void brokenServerDoesNotPreventStartup() {
        McpClientRegistry registry = context.getBean(McpClientRegistry.class);

        McpServerStatus broken = registry.statuses().get("broken");
        assertThat(broken).isNotNull();
        assertThat(broken.available()).isFalse();
        assertThat(broken.failureReason()).isNotBlank();
        assertThat(registry.clients()).doesNotContainKey("broken");

        // 故障隔离：另一个 server 不受影响
        assertThat(registry.isAvailable("test")).isTrue();
    }

    @Test
    @DisplayName("无模型场景：MCP 组件仍初始化、工具仍可列举（只是没有对话能用它）")
    void mcpWorksWithoutChatModel() {
        AiChatClientProvider chatProvider = context.getBean(AiChatClientProvider.class);
        assertThat(chatProvider.isConfigured()).as("未配置模型：聊天能力不可用").isFalse();

        McpToolCallbackProvider tools = context.getBean(McpToolCallbackProvider.class);
        assertThat(tools.toolNames()).as("没有模型不影响 MCP 工具列举")
                .contains("mcp_test_echo", "mcp_test_ping", "mcp_test_record");
    }

    @Test
    @DisplayName("冲突校验在正常装配时通过，并如实报出工具数")
    void conflictValidatorReportsCleanState() {
        McpToolConflictValidator validator = context.getBean(McpToolConflictValidator.class);

        // helper server 声明 4 个工具（含一个 schema 缺 type 的——SDK 转换时已归一化，照样装进来）
        assertThat(validator.mcpToolNames()).hasSize(4);
    }
}
