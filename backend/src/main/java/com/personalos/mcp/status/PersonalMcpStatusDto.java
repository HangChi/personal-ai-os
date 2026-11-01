package com.personalos.mcp.status;

import java.util.List;

/** Runtime configuration and tool catalog for the built-in MCP Server. */
public record PersonalMcpStatusDto(boolean enabled,
                                   String name,
                                   String version,
                                   String endpoint,
                                   List<PersonalMcpToolDto> tools) {
}
