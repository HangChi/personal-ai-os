package com.personalos.mcp.tool;

import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.confirmation.McpConfirmationService;
import com.personalos.mcp.client.McpClientRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP 工具的装饰 {@link ToolCallback}：命名 + 策略 + 审计 + 确认，四个横切关注点在这一层统一发生。
 *
 * <p><b>为什么需要这一层</b>（宪法原则 II）：模型 MUST NOT 直接访问外部系统，所有副作用 MUST 经
 * 显式 Java 接口暴露的工具。MCP 工具由外部 server 声明，不满足「显式 Java 接口」；
 * 经本类包装后，每个 MCP 工具都是一个显式 Java 对象的方法，副作用路径重新收敛到本进程内。
 *
 * <p><b>执行路径</b>（策略决定）：
 * <ul>
 *   <li>{@link McpToolPolicy#AUTO}：直接调底层 MCP 工具，结果进入对话；</li>
 *   <li>{@link McpToolPolicy#CONFIRM}：**不触达 server**，生成待确认事项并返回
 *       {@code REQUIRES_CONFIRMATION}；真正执行由人类在界面上确认后经
 *       {@code McpConfirmationHandler} 完成。</li>
 * </ul>
 *
 * <p>两条路径都经 {@link ToolExecutor}：与内置工具同一套幂等 + 审计语义
 * （{@code operation_record}），工具名带 {@code mcp_} 前缀即可区分来源。
 */
public class McpToolCallback implements ToolCallback {

    private final McpClientRegistry registry;
    private final ToolCallback delegate;
    private final String serverName;
    private final String originalToolName;
    private final String assembledName;
    private final McpToolPolicy policy;
    private final ToolExecutor toolExecutor;
    private final McpConfirmationService confirmationService;
    private final JsonCodec jsonCodec;

    public McpToolCallback(ToolCallback delegate,
                           String serverName,
                           String originalToolName,
                           McpToolPolicy policy,
                           ToolExecutor toolExecutor,
                           McpConfirmationService confirmationService,
                           JsonCodec jsonCodec, McpClientRegistry registry) {
        this.registry = registry;
        this.delegate = delegate;
        this.serverName = serverName;
        this.originalToolName = originalToolName;
        this.assembledName = McpToolNaming.prefixedName(serverName, originalToolName);
        this.policy = policy;
        this.toolExecutor = toolExecutor;
        this.confirmationService = confirmationService;
        this.jsonCodec = jsonCodec;
    }

    /** @return 加了前缀的装配名（模型看到的工具名） */
    public String assembledName() {
        return assembledName;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition original = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(assembledName)
                .description(original.description())
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> arguments = parseArguments(toolInput);

        if (policy == McpToolPolicy.CONFIRM) {
            // 与内置可确认工具（TaskTools.deleteTask）同一条路径：申请执行，而非执行。
            // 工具层幂等用派生键——同轮内同工具同参数的重复调用只对应一个待确认事项。
            return toolExecutor.write(assembledName, arguments, toolContext, () -> {
                ConfirmationDto confirmation = confirmationService.requestToolCall(
                        serverName, originalToolName, arguments, ToolExecutor.conversationId(toolContext));
                return ToolExecutionResult.requiresConfirmation(
                        "该操作会修改外部系统，需要你确认。已生成待确认事项：" + confirmation.summary()
                                + "。请用户在界面上确认后再执行。",
                        confirmation.id().toString());
            });
        }

        return toolExecutor.read(assembledName, arguments, toolContext, () -> {
            // 每次解析当前客户端：重连后已装配的回调也必须使用新连接。
            var client = registry.clients().get(serverName);
            if (client == null) return ToolExecutionResult.error("MCP server 不可用，请在设置中重连：" + serverName);
            try {
                var result = client.callTool(McpSchema.CallToolRequest.builder(originalToolName)
                        .arguments(arguments).build());
                String raw = jsonCodec.toJson(result);
                if (Boolean.TRUE.equals(result.isError())) {
                    // 工具业务失败不等于连接已断开，不关闭仍然健康的客户端。
                    return ToolExecutionResult.error("MCP server 执行失败：" + raw);
                }
                return ToolExecutionResult.ok(raw, raw);
            } catch (RuntimeException ex) {
                registry.markUnavailable(serverName, client, ex.getMessage());
                return ToolExecutionResult.error("MCP server 连接失败，请重连后重试：" + serverName);
            }
        });
    }

    /**
     * 解析模型传入的参数 JSON。
     *
     * <p>失败按空参数继续而不是报错：server 侧会因缺 required 参数给出明确错误，
     * 那条错误比在这里猜「模型想传什么」更可靠。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = jsonCodec.fromJson(toolInput, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
