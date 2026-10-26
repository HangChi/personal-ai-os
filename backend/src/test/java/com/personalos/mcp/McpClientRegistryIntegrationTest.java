package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.client.McpServerStatus;
import com.personalos.mcp.config.McpConfigValidator;
import com.personalos.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link McpClientRegistry} 集成测试：真实 stdio 协议，零网络零外部进程依赖。
 *
 * <p>测试 server 是 {@link TestMcpServerMain}——以本 JVM 的类路径派生的子进程，
 * 客户端走与生产完全相同的路径（进程派生 + stdio 帧）。不依赖 npx / 网络（用户决策③）。
 *
 * <p>覆盖 spec 的装配契约 §2.2 与生命周期契约 L1/L2/L3：
 * 连接列举、工具调用、关闭后无残留（关闭标记文件是硬证据）、
 * 命令不存在降级、挂起超时降级。
 */
class McpClientRegistryIntegrationTest {

    private static final String JAVA = System.getProperty("java.home") + "/bin/java";

    @TempDir
    Path tempDir;

    private McpProperties properties(String name, String command, List<String> args, Duration timeout) {
        return new McpProperties(true, Map.of(name,
                new McpProperties.ServerConfig(command, args, Map.of(), Set.of(), timeout)));
    }

    private List<String> spawnHelperArgs(Path marker) {
        return List.of("-cp", System.getProperty("java.class.path"),
                TestMcpServerMain.class.getName(), marker.toString(), tempDir.resolve("journal.txt").toString());
    }

    @Test
    @DisplayName("连接成功：列举 server 暴露的全部工具（echo + ping + record + 坏 schema 共 4 个）、调用 echo 返回真实结果")
    void connectsListsToolsAndCalls() {
        Path marker = tempDir.resolve("closed.marker");
        McpProperties properties = properties("test", JAVA, spawnHelperArgs(marker), Duration.ofSeconds(15));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());
        registry.connectAll();

        McpServerStatus status = registry.statuses().get("test");
        assertThat(status).isNotNull();
        assertThat(status.available()).isTrue();
        // 注册表报的是 server **声明**的工具数；坏 schema 的剔除发生在装配层（provider），
        // 两者刻意分开：连接层的职责是如实转述 server，质检放在装配层
        assertThat(status.toolCount()).isEqualTo(4);
        assertThat(registry.isAvailable("test")).isTrue();

        McpSchema.CallToolResult result = registry.clients().get("test").callTool(
                McpSchema.CallToolRequest.builder("echo").arguments(Map.of("text", "hello")).build());
        assertThat(result.content()).hasSize(1);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("echo:hello");

        registry.destroy();
    }

    @Test
    @DisplayName("关闭后无残留：server 子进程真的退出（shutdown hook 标记文件为证）")
    void destroyTerminatesServerProcess() throws Exception {
        Path marker = tempDir.resolve("closed.marker");
        McpProperties properties = properties("test", JAVA, spawnHelperArgs(marker), Duration.ofSeconds(15));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());
        registry.connectAll();
        registry.destroy();

        // 不假设 server 礼貌——等 shutdown hook 的硬证据，最多 10 秒
        long deadline = System.currentTimeMillis() + 10_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(Files.exists(marker))
                .as("server 子进程退出后由 shutdown hook 写入标记文件")
                .isTrue();
    }

    @Test
    @DisplayName("命令不存在：该 server 标记不可用，不抛异常（可用性问题不拖垮启动）")
    void brokenCommandMarksUnavailable() {
        McpProperties properties = properties("broken", "/nonexistent/mcp-server", List.of(), Duration.ofSeconds(5));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());
        registry.connectAll();

        McpServerStatus status = registry.statuses().get("broken");
        assertThat(status.available()).isFalse();
        assertThat(status.failureReason()).isNotBlank();
        assertThat(registry.clients()).doesNotContainKey("broken");
    }

    @Test
    @DisplayName("server 挂起：初始化超时后标记不可用（启动不得被无限阻塞）")
    void hangingServerTimesOut() {
        // sleep 不讲 MCP 协议：initialize 握手在 initializationTimeout 后失败。
        // sleep 1 保证即便传输没来得及杀进程，它也会自行退出，不在测试机上留孤儿。
        McpProperties properties = properties("hang", "/bin/sleep", List.of("1"), Duration.ofMillis(400));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());
        registry.connectAll();

        assertThat(registry.statuses().get("hang").available()).isFalse();
    }

    @Test
    @DisplayName("多 server 故障隔离：一个挂了，另一个照常可用")
    void oneServerFailureDoesNotAffectOthers() {
        Path marker = tempDir.resolve("closed.marker");
        McpProperties properties = new McpProperties(true, Map.of(
                "good", new McpProperties.ServerConfig(JAVA, spawnHelperArgs(marker), Map.of(), Set.of(),
                        Duration.ofSeconds(15)),
                "bad", new McpProperties.ServerConfig("/nonexistent/mcp-server", List.of(), Map.of(), Set.of(),
                        Duration.ofSeconds(5))));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());
        registry.connectAll();

        assertThat(registry.isAvailable("good")).isTrue();
        assertThat(registry.isAvailable("bad")).isFalse();

        registry.destroy();
    }

    @Test
    @DisplayName("配置缺 command：启动期校验直接失败（用户错误不降级）")
    void missingCommandFailsValidation() {
        McpProperties properties = new McpProperties(true, Map.of(
                "oops", new McpProperties.ServerConfig(null, List.of(), Map.of(), Set.of(), null)));

        McpClientRegistry registry = new McpClientRegistry(properties, new McpConfigValidator());

        assertThatThrownBy(registry::connectAll)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode().getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.getCode());
    }
}
