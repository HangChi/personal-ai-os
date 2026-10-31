package com.personalos.mcp.tool;

import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.config.McpProperties;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.confirmation.McpConfirmationService;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把已连接的 MCP server 的工具包装成 Spring AI 的 {@link ToolCallbackProvider}。
 *
 * <p>每个工具的转换链：SDK 的 {@link SyncMcpToolCallbackProvider}（MCP 协议 → ToolCallback）
 * → 我们的 {@link McpToolCallback} 装饰（命名 / 策略 / 审计 / 确认）。
 * 前缀由装饰层负责，故这里用**不带**前缀生成器的构造——命名只有一处真相。
 *
 * <p>schema 自检（spec Edge Cases）：server 声明的工具缺 {@code type: object} 或
 * {@code properties} 不是对象时，该工具剔除并告警，不把坏工具暴露给模型。
 */
public class McpToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackProvider.class);

    private final List<McpToolCallback> callbacks;

    private McpToolCallbackProvider(List<McpToolCallback> callbacks) {
        this.callbacks = List.copyOf(callbacks);
    }

    /**
     * 从已连接的客户端构建工具提供者。
     *
     * @param registry           MCP 注册表；回调每次调用从中解析当前客户端
     * @param properties         配置（autoTools 策略清单来源）
     * @param toolExecutor       幂等 + 审计外壳
     * @param confirmationService 变更类工具的确认申请入口
     * @param jsonCodec          JSON 编解码（schema 自检用）
     */
    public static McpToolCallbackProvider create(McpClientRegistry registry,
                                                 McpProperties properties,
                                                 ToolExecutor toolExecutor,
                                                 McpConfirmationService confirmationService,
                                                 JsonCodec jsonCodec) {
        List<McpToolCallback> wrapped = new ArrayList<>();
        for (Map.Entry<String, McpSyncClient> entry : registry.clients().entrySet()) {
            String serverName = entry.getKey();
            McpProperties.ServerConfig config = properties.servers().get(serverName);
            Set<String> autoTools = config == null ? Set.of() : config.autoTools();

            // 2026-10-26：spring-ai-mcp 2.0 把 SyncMcpToolCallbackProvider 的**全部**构造器
            // 都标成了已过时（含 varargs 与 List 重载），改走 Builder。两个非默认项都是刻意的：
            //   - 保持默认的「接受全部工具」：白名单/黑名单由本项目的 McpToolPolicy 决定；
            //   - noPrefix()：前缀由 McpToolNaming 统一加（server__tool），
            //     框架再加一次会变成 server__server__tool，模型和策略表都对不上。
            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider.Builder()
                    .mcpClients(List.of(entry.getValue()))
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .build();
            for (ToolCallback raw : provider.getToolCallbacks()) {
                String originalName = raw.getToolDefinition().name();
                if (!hasUsableSchema(raw.getToolDefinition(), jsonCodec)) {
                    log.warn("MCP 工具 schema 异常，已剔除：server={}, tool={}", serverName, originalName);
                    continue;
                }
                McpToolPolicy policy = McpToolPolicy.resolve(originalName, autoTools);
                wrapped.add(new McpToolCallback(raw, serverName, originalName, policy,
                        toolExecutor, confirmationService, jsonCodec, registry));
                log.info("MCP 工具已装配：{}（策略 {}）",
                        McpToolNaming.prefixedName(serverName, originalName), policy);
            }
        }
        return new McpToolCallbackProvider(wrapped);
    }

    /** 供启动期冲突校验使用：全部装配名（已加前缀） */
    public List<String> toolNames() {
        return callbacks.stream().map(McpToolCallback::assembledName).toList();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * 最小 schema 检查：是 JSON 对象、{@code type} 为 {@code object}、{@code properties} 是对象。
     *
     * <p><b>实测结论（2026-10-15）</b>：Spring AI 的 {@code SyncMcpToolCallbackProvider}
     * 转换时会**归一化 schema**——server 若声明缺 {@code type} 的 schema，
     * 到达本层时已被补成 {@code {"type":"object",...}}（实测打印确认）。
     * 也就是说「server 声明坏 schema」这条路径在当前依赖版本下**不可达**；
     * 本检查因此退化为**防御性**的：防的是 JSON 损坏/空白 schema，以及未来框架行为变化。
     * 它刻意保留——成本是一次字符串解析，收益是把「坏工具暴露给模型」这类
     * 最难排查的问题挡在装配期。
     *
     * <p>刻意薄：深度校验 schema 是 server 的职责，我们只保证「不把明显坏的东西交给模型」。
     */
    @SuppressWarnings("unchecked")
    static boolean hasUsableSchema(ToolDefinition definition, JsonCodec jsonCodec) {
        String schema = definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> parsed = jsonCodec.fromJson(schema, Map.class);
            if (parsed == null || !"object".equals(parsed.get("type"))) {
                return false;
            }
            return parsed.get("properties") instanceof Map<?, ?>;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
