package com.personalos.mcp.confirmation;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationActionHandler;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * MCP 工具调用确认的**执行**处理器：用户在界面上点「确认」后，真正调用那个 MCP 工具。
 *
 * <p><b>为什么无条件注册</b>（不跟 {@code McpClientRegistry} 一样加条件注解）：
 * {@code ConfirmationRegistry} 在启动期校验「每个 action 都有处理器」——
 * 若本处理器也只在 MCP 启用时存在，那么**默认关闭 MCP 时应用会因
 * 「MCP_TOOL_CALL 没有处理器」而启动失败**，直接违反 SC-03（零启用 = 零变化）。
 * 处理器常在，执行时再按需取 registry：MCP 关闭时取不到，报明确错误
 * （而这种确认记录在关闭状态下根本不可能被创建）。
 */
@Component
public class McpConfirmationHandler implements ConfirmationActionHandler {

    private static final Logger log = LoggerFactory.getLogger(McpConfirmationHandler.class);

    private final ObjectProvider<McpClientRegistry> registryProvider;
    private final JsonCodec jsonCodec;

    public McpConfirmationHandler(ObjectProvider<McpClientRegistry> registryProvider, JsonCodec jsonCodec) {
        this.registryProvider = registryProvider;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Set<ConfirmationAction> supportedActions() {
        return Set.of(ConfirmationAction.MCP_TOOL_CALL);
    }

    @Override
    public int execute(ConfirmationAction action, String payloadJson) {
        McpToolCall payload = jsonCodec.fromJson(payloadJson, McpToolCall.class);
        McpClientRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.AI_NOT_CONFIGURED,
                    "MCP 未启用，无法执行该确认操作");
        }
        McpSyncClient client = registry.clients().get(payload.server());
        if (client == null) {
            // 目标在确认前变得不可用（server 挂了或被移除）：抛出资源不存在，
            // ConfirmationService 会把它转成「目标已不存在」的优雅结果
            throw new ResourceNotFoundException("MCP server 不可用：" + payload.server());
        }

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(payload.tool(), payload.arguments()));
        log.info("已执行 MCP 待确认调用 server={} tool={} isError={}",
                payload.server(), payload.tool(), Boolean.TRUE.equals(result.isError()));
        return 1;
    }
}
