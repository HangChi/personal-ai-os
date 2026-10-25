package com.personalos.mcp.status;

import java.util.List;

/**
 * 单个 MCP server 的运行期状态视图。
 *
 * <p><b>刻意不包含 {@code env} / {@code command} / {@code args}</b>：
 * {@code env} 是 {@code MCP_SERVERS_<NAME>_ENV_*} 的落点，可能装着第三方密钥；
 * {@code command} 与 {@code args} 含本机路径。页面需要的是「连上没有、几个工具、哪些只读」，
 * 这三样分别由 {@code available} / {@code toolCount} / {@code autoTools} 回答，足以判断状态。
 *
 * @param name          server 名（配置键）
 * @param available     是否连接成功并列到了工具
 * @param failureReason 不可用原因；可用时为 null
 * @param toolCount     暴露的工具数；不可用时为 0
 * @param autoTools     配置中声明为只读直行的工具名（按字典序，保证输出稳定）
 */
public record McpServerStatusDto(String name,
                                 boolean available,
                                 String failureReason,
                                 int toolCount,
                                 List<String> autoTools) {
}
