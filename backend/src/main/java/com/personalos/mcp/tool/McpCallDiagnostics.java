package com.personalos.mcp.tool;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded, untrusted diagnostics; an error is never proof that a remote write rolled back. */
public final class McpCallDiagnostics {
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^\\s/@]+@+");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?#]+)[?#][^\\s]*");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:authorization|proxy-authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|cookie)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*(?:\\\"|$)|'[^']*(?:'|$)|[^\\s,;}]+)");

    private McpCallDiagnostics() {}

    public static McpError protocolError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof McpError mcp && mcp.getJsonRpcError() != null) return mcp;
        }
        return null;
    }

    public static Map<String, Object> toolError(String server, String tool, McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        if (result != null && result.content() != null) {
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent item && item.text() != null) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(prefix(item.text(), Math.max(0, 4096 - text.codePointCount(0, text.length()))));
                    if (text.codePointCount(0, text.length()) >= 4096) break;
                }
            }
        }
        return data(server, tool, "TOOL_ERROR", text.toString());
    }

    public static Map<String, Object> callError(String server, String tool, Throwable error) {
        McpError protocol = protocolError(error);
        if (protocol != null) {
            var rpc = protocol.getJsonRpcError();
            return data(server, tool, "PROTOCOL_ERROR", "JSON-RPC " + rpc.code() + ": " + rpc.message());
        }
        // Transport exceptions can contain commands, headers and credentials. Do not forward them.
        return data(server, tool, "CONNECTION_ERROR", "MCP 连接中断或调用超时，请检查服务状态。未自动重试。");
    }

    private static Map<String, Object> data(String server, String tool, String kind, String raw) {
        String detail = sanitize(raw);
        if (detail.isBlank()) detail = "远端工具报告失败，未提供文本原因。";
        return Map.of("mcpError", Map.of("server", server, "tool", tool, "kind", kind,
                "detail", detail, "untrusted", true));
    }

    public static String sanitize(String raw) {
        String value = raw == null ? "" : prefix(raw, 4096);
        value = URL_CREDENTIALS.matcher(value).replaceAll("$1[redacted]@");
        value = URL_QUERY.matcher(value).replaceAll("$1?[redacted]");
        value = AUTH.matcher(value).replaceAll("$1 [redacted]");
        value = SECRET.matcher(value).replaceAll("$1[redacted]");
        value = value.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("(?U)\\s+", " ").trim();
        int count = value.codePointCount(0, value.length());
        return count > 1200 ? value.substring(0, value.offsetByCodePoints(0, 1200)) + "…" : value;
    }

    private static String prefix(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
