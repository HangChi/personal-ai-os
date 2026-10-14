package com.personalos.mcp.client;

/**
 * MCP server 的运行状态（进程内事实，不落库）。
 *
 * <p>两类故障刻意区别对待（spec research.md R-07）：
 * 连不上 / 超时 → {@code available=false} 降级运行，应用照常启动；
 * 工具名冲突 → 启动失败（正确性问题不允许降级，见阶段 6 Phase 2）。
 *
 * @param name          server 名（配置键）
 * @param available     是否连接成功并列到工具
 * @param failureReason 不可用原因；可用时为 null
 * @param toolCount     该 server 暴露的工具数；不可用时为 0
 */
public record McpServerStatus(String name, boolean available, String failureReason, int toolCount) {

    public static McpServerStatus available(String name, int toolCount) {
        return new McpServerStatus(name, true, null, toolCount);
    }

    public static McpServerStatus unavailable(String name, String failureReason) {
        return new McpServerStatus(name, false, failureReason, 0);
    }
}
