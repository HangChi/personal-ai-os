package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.config.McpProperties;
import com.personalos.mcp.confirmation.McpConfirmationService;
import com.personalos.mcp.tool.McpToolCallback;
import com.personalos.mcp.tool.McpToolCallbackProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MCP 策略 + 确认链路的端到端集成测试。
 *
 * <p><b>链路全部是真的</b>：应用按真实配置装配（{@code mcp.enabled=true} + server 配置经
 * {@code @DynamicPropertySource} 注入），registry 是容器里的那个 bean——与
 * {@code McpConfirmationHandler} 执行时取到的是**同一个实例**。工具走真实的
 * stdio 子进程 server（{@link TestMcpServerMain}）与真实的
 * {@code SyncMcpToolCallbackProvider} 协议转换；确认走真实的两阶段机制（Testcontainers 上的
 * {@code ConfirmationService}：CAS 占位 / 令牌 / 单次性）。
 *
 * <p><b>断言落在可观测的副作用上</b>：CONFIRM 工具（{@code record}）拦截时日志文件不得出现，
 * 用户确认后才出现——「没执行」不是读代码看出来的，是文件系统作证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mcp.enabled=true")
@Testcontainers
class McpConfirmationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final String JAVA = System.getProperty("java.home") + "/bin/java";

    /** surefire 的工作目录是 backend/，因此这些文件落在 target/ 下（已被 git 忽略） */
    private static final Path WORK_DIR = Path.of("target", "mcp-it");
    private static final Path MARKER = WORK_DIR.resolve("closed.marker");
    private static final Path JOURNAL = WORK_DIR.resolve("journal.txt");

    @DynamicPropertySource
    static void mcpServerProperties(DynamicPropertyRegistry registry) throws Exception {
        // 目录必须先存在：子进程在上下文启动时就拉起了，晚于此处的 @BeforeEach 来不及
        Files.createDirectories(WORK_DIR);
        String classpath = System.getProperty("java.class.path");
        if (classpath.contains(",")) {
            // args 是逗号分隔的字符串属性；类路径里出现逗号会让参数被拆错。
            // 刻意响亮失败：这种环境下应当改用别的方式注入参数，而不是让它静默跑歪。
            throw new IllegalStateException("测试类路径含逗号，无法用逗号分隔的属性注入 args");
        }
        registry.add("mcp.servers.test.command", () -> JAVA);
        registry.add("mcp.servers.test.args", () -> String.join(",", List.of(
                "-cp", classpath,
                TestMcpServerMain.class.getName(),
                MARKER.toString(), JOURNAL.toString())));
        registry.add("mcp.servers.test.auto-tools", () -> "echo,ping");
    }

    @Autowired
    private McpClientRegistry registry;

    @Autowired
    private McpProperties mcpProperties;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private ConfirmationService confirmationService;

    @Autowired
    private ConfirmationRepository confirmationRepository;

    @Autowired
    private OperationRecordRepository operationRecordRepository;

    @Autowired
    private JsonCodec jsonCodec;

    @BeforeEach
    void cleanState() throws Exception {
        confirmationRepository.deleteAll();
        operationRecordRepository.deleteAll();
        Files.deleteIfExists(JOURNAL);
        Files.deleteIfExists(MARKER);
    }

    private McpToolCallbackProvider provider() {
        return McpToolCallbackProvider.create(registry, mcpProperties,
                toolExecutor, new McpConfirmationService(confirmationService), jsonCodec);
    }

    private ToolCallback find(McpToolCallbackProvider provider, String assembledName) {
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if (callback.getToolDefinition().name().equals(assembledName)) {
                return callback;
            }
        }
        throw new IllegalStateException("provider 里没有工具：" + assembledName);
    }

    @Test
    @DisplayName("已装配的 AUTO 工具断连后更新状态，重连后同一回调使用新客户端")
    void existingCallbackRecoversAfterReconnect() {
        var callback = find(provider(), "mcp_test_echo");
        var previous = registry.clients().get("test");
        previous.close();
        try {
            assertThat(callback.call("{\"text\":\"lost\"}", null)).contains("ERROR");
            assertThat(registry.isAvailable("test")).isFalse();
            registry.reconnect("test");
            var current = registry.clients().get("test");
            assertThat(current).isNotSameAs(previous);
            // 旧连接的迟到错误不会关闭新连接。
            registry.markUnavailable("test", previous, "迟到错误");
            assertThat(registry.isAvailable("test")).isTrue();
            assertThat(callback.call("{\"text\":\"recovered\"}", null)).contains("OK", "echo:recovered");
        } finally {
            if (!registry.isAvailable("test")) registry.reconnect("test");
        }
    }

    @Test
    @DisplayName("CONFIRM 全链路：拦截不执行 → 确认后才真正调用 → 副作用落盘 → 重复确认被拒")
    void confirmToolCallRunsFullChain() throws Exception {
        assertThat(registry.isAvailable("test")).isTrue();
        McpToolCallbackProvider provider = provider();
        ToolCallback record = find(provider, "mcp_test_record");

        // 1) 调用即拦截：不触达 server
        String intercepted = record.call("{\"text\":\"first\"}", null);
        assertThat(intercepted).contains("REQUIRES_CONFIRMATION");
        assertThat(Files.exists(JOURNAL))
                .as("拦截阶段不得触达 server——副作用文件不应出现")
                .isFalse();

        // 2) 待确认记录：摘要有 server 与工具名（用户在点确认前必须知道是什么操作）
        ConfirmationDto pending = confirmationService.list(null, ConfirmationStatus.PENDING).stream()
                .filter(c -> "MCP_TOOL_CALL".equals(c.action()))
                .findFirst()
                .orElseThrow();
        assertThat(pending.summary())
                .contains("server=test").contains("工具=record").contains("text=first");

        // 3) 确认 → 真正执行 → 副作用落盘
        var execution = confirmationService.confirm(pending.id(), pending.token());
        assertThat(execution.executed()).isTrue();
        assertThat(Files.readString(JOURNAL).trim()).isEqualTo("recorded:first");

        // 4) 重复确认被拒（单次性）
        assertThatThrownBy(() -> confirmationService.confirm(pending.id(), pending.token()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFIRMATION_NOT_ACTIVE);

        // 5) 审计归属（与内置可确认工具完全一致，无 MCP 特例）：
        //    拦截记进 operation_record（工具调用审计），执行记在 confirmation 表的状态流转。
        //    内置的 deleteTask 也是这个口径——执行侧不写工具审计，避免两处状态各自演化。
        List<OperationRecordEntity> records = operationRecordRepository.findAll().stream()
                .filter(r -> r.getOperation().startsWith("mcp_test_record"))
                .toList();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getOperation()).as("工具名带前缀，来源可辨").isEqualTo("mcp_test_record");
        assertThat(records.get(0).getOutcome().name()).isEqualTo("REQUIRES_CONFIRMATION");

        assertThat(confirmationRepository.findById(pending.id()).orElseThrow().getStatus().name())
                .as("执行痕迹在 confirmation 表：状态已流转为 CONFIRMED")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("AUTO 全链路：直接执行不弹确认，结果进入信封，零待确认记录")
    void autoToolExecutesDirectly() {
        McpToolCallbackProvider provider = provider();

        String result = find(provider, "mcp_test_echo").call("{\"text\":\"hello\"}", null);

        assertThat(result).contains("echo:hello").doesNotContain("REQUIRES_CONFIRMATION");
        assertThat(confirmationService.list(null, ConfirmationStatus.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("server 侧错误：转成可读错误结果且保留原始原因（审计能区分挂了还是参数不对）")
    void serverErrorSurfacesReadableMessage() {
        McpToolCallbackProvider provider = provider();

        // 缺 required 参数 text：server 报参数错误
        String result = find(provider, "mcp_test_echo").call("{}", null);

        assertThat(result).contains("ERROR").contains("MCP server 执行失败");
    }

    @Test
    @DisplayName("装配：工具全部就位（含 SDK 已归一化 schema 的那个）；record 未列入 autoTools 故走 CONFIRM")
    void toolNamesAndPoliciesAssembled() {
        McpToolCallbackProvider provider = provider();

        // 实测结论：helper 里那个「故意缺 type」的工具，经 Spring AI 转换层时已被补成
        // {"type":"object",...}（SDK 会归一化 schema），因此它照样装配成功。
        // 也就是说「坏 schema 被剔除」这条路在集成测试里构造不出来——那部分自检由
        // McpToolCallbackProviderTest 直接单测（防御性代码必须能被直接测到）。
        assertThat(provider.toolNames())
                .containsExactlyInAnyOrder("mcp_test_echo", "mcp_test_ping",
                        "mcp_test_record", "mcp_test_broken_schema");
        assertThat(((McpToolCallback) find(provider, "mcp_test_record")).assembledName())
                .isEqualTo("mcp_test_record");
    }

    @Test
    @DisplayName("工具名冲突：与内置工具同名即启动失败，错误信息给出冲突项（正确性问题不降级）")
    void conflictWithBuiltinToolBlocksStartup() {
        // 一个刻意重名的「内置工具」：名字与 MCP 装配名 mcp_test_echo 完全相同
        com.personalos.common.ai.ToolContributor colliding = () -> List.of(new CollidingTools());
        var validator = new com.personalos.mcp.config.McpToolConflictValidator(
                List.of(colliding), provider());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp_test_echo")
                .hasMessageContaining("与内置工具同名");
    }

    /** 刻意与 MCP 装配名重名的工具，仅供冲突用例使用 */
    static class CollidingTools {
        @org.springframework.ai.tool.annotation.Tool(name = "mcp_test_echo",
                description = "刻意重名，用于验证启动期冲突校验")
        public String collide() {
            return "never-called";
        }
    }
}
