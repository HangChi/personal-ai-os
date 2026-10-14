package com.personalos.mcp.config;

import com.personalos.common.ai.ToolContributor;
import com.personalos.mcp.tool.McpToolCallbackProvider;
import com.personalos.mcp.tool.McpToolNaming;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动期工具名冲突校验：MCP 装配名与内置工具名（或 MCP 之间）重名即**启动失败**。
 *
 * <p><b>为什么是启动失败而不是降级</b>（research.md R-07）：同名即歧义，模型按名调用时
 * 会落到错误的工具上——症状是「结果莫名其妙」，属于最难排查的一类。本项目的一贯立场是
 * 「静默失效比响亮失败更糟」，正确性问题不允许降级运行。
 * 与之相对，server 连不上只降级（可用性问题不该拖垮启动）。
 *
 * <p><b>为什么校验在这里而不是 {@code AiChatClientProvider}</b>：{@code common} 不能依赖
 * {@code mcp}（模块方向），而本类属于 mcp 模块、依赖 {@code common} 的 SPI，
 * 方向正确。内置工具名经 {@link ToolContributor} 收集——这正是该 SPI 存在的意义。
 */
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpToolConflictValidator {

    private static final Logger log = LoggerFactory.getLogger(McpToolConflictValidator.class);

    private final List<ToolContributor> toolContributors;
    private final McpToolCallbackProvider mcpToolCallbackProvider;

    public McpToolConflictValidator(List<ToolContributor> toolContributors,
                                    McpToolCallbackProvider mcpToolCallbackProvider) {
        this.toolContributors = toolContributors;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    /**
     * 校验工具名冲突。正常路径由 Spring 在 Bean 创建后调用；公开是便于测试与运维诊断。
     *
     * @throws IllegalStateException 存在冲突时（应用启动会被阻止——这是刻意设计）
     */
    @PostConstruct
    public void validate() {
        Set<String> builtinNames = builtinToolNames();
        List<String> mcpNames = mcpToolCallbackProvider.toolNames();

        List<String> conflicts = McpToolNaming.conflicts(builtinNames, mcpNames);
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "MCP 工具与既有工具重名，已阻止启动（模型按名调用时会落到错误的工具上）。冲突项："
                            + String.join("；", conflicts));
        }

        log.info("MCP 工具装配完成：共 {} 个，内置工具 {} 个，无命名冲突",
                mcpNames.size(), builtinNames.size());
    }

    /** 供测试与诊断：当前全部 MCP 装配名 */
    public List<String> mcpToolNames() {
        return new ArrayList<>(mcpToolCallbackProvider.toolNames());
    }

    private Set<String> builtinToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolContributor contributor : toolContributors) {
            for (ToolCallback callback : ToolCallbacks.from(contributor.toolObjects().toArray())) {
                names.add(callback.getToolDefinition().name());
            }
        }
        return names;
    }
}
