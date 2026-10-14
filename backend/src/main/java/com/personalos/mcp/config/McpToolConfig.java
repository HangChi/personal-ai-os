package com.personalos.mcp.config;

import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.confirmation.McpConfirmationService;
import com.personalos.mcp.tool.McpToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具装配：把装饰后的工具以 {@code ToolCallbackProvider} 的形态暴露给容器。
 *
 * <p>{@code AiChatClientProvider}（在 {@code common}）会用 {@code ObjectProvider}
 * 收集所有 {@code ToolCallbackProvider} ——它不认识 MCP 这个概念，
 * 因此阶段 6 不需要改动对话模块的依赖方向。
 */
@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpToolConfig {

    @Bean
    public McpToolCallbackProvider mcpToolCallbackProvider(McpClientRegistry registry,
                                                           McpProperties properties,
                                                           ToolExecutor toolExecutor,
                                                           McpConfirmationService confirmationService,
                                                           JsonCodec jsonCodec) {
        return McpToolCallbackProvider.create(registry.clients(), properties,
                toolExecutor, confirmationService, jsonCodec);
    }
}
