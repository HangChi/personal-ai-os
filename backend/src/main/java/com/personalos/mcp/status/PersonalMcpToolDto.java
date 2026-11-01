package com.personalos.mcp.status;

/** Public metadata for a tool exposed by the Personal AI OS MCP Server. */
public record PersonalMcpToolDto(String name, String description, boolean readOnly) {
}
