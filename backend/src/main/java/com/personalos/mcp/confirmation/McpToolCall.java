package com.personalos.mcp.confirmation;

import java.util.Map;

/**
 * MCP 工具调用确认的 payload（存入 {@code confirmation.payload_json}，确认时原样回放）。
 *
 * <p>用自由 JSON 而不是新列：不同 MCP 工具的参数结构完全不同（filesystem 是
 * {@code {path, content}}，GitHub 是 {@code {owner, repo, ...}}），
 * 列承载会逼出「万能列」或稀疏表。
 *
 * @param server    server 名（配置键）
 * @param tool      原始工具名（server 声明的名字）
 * @param arguments 模型传入的参数（确认后原样交给 server）
 */
public record McpToolCall(String server, String tool, Map<String, Object> arguments) {
}
