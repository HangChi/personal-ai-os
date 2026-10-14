package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.client.McpServerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MCP 启动行为：server 连不上时**应用照常启动**。
 *
 * <p>spec 的两类故障区别对待（research.md R-07）：配置错误 → 启动失败（由
 * {@code McpClientRegistryIntegrationTest} 的校验用例覆盖）；单个 server 连接失败 → 降级。
 * 本测试守住后者：一个坏 server 不该让用户的整个 OS 起不来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.enabled=true",
                "mcp.servers.broken.command=/nonexistent/mcp-server",
        })
@Testcontainers
class McpDegradedStartupIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final ApplicationContext context;

    @Autowired
    McpDegradedStartupIntegrationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("坏 server 不拖垮启动：上下文就绪，注册表在册，该 server 标记不可用")
    void brokenServerDoesNotPreventStartup() {
        McpClientRegistry registry = context.getBean(McpClientRegistry.class);

        McpServerStatus status = registry.statuses().get("broken");
        assertThat(status).isNotNull();
        assertThat(status.available()).isFalse();
        assertThat(status.failureReason()).isNotBlank();
        assertThat(registry.clients()).doesNotContainKey("broken");
    }
}
