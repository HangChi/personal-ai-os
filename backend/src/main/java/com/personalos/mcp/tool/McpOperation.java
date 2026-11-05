package com.personalos.mcp.tool;

import java.util.Map;

/** Application metadata: downstream servers must explicitly honor this key. */
public final class McpOperation {
    public static final String META_KEY = "personal-ai-os/idempotency-key";
    private McpOperation() {}

    public static Map<String, Object> metadata(String operationId) {
        return operationId == null || operationId.isBlank() ? Map.of() : Map.of(META_KEY, operationId);
    }

    public static String unknown(String tool) {
        return "MCP 工具执行结果未确认：" + tool + "。外部系统可能已发生变更，请先核对目标状态，不要直接重试或重新发起。";
    }
}
