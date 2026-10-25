package com.personalos.mcp.status;

import java.util.List;

/**
 * MCP 接入状态的顶层视图。
 *
 * <p>{@code enabled=false} 时 {@code servers} 恒为空列表 —— 前端据此区分
 * 「没开这个功能」与「开了但一个 server 都没配」，两者给用户的下一步动作完全不同。
 *
 * @param enabled 总开关（{@code MCP_ENABLED}）
 * @param servers 已声明 server 的状态（按名字字典序）
 */
public record McpStatusDto(boolean enabled, List<McpServerStatusDto> servers) {
}
