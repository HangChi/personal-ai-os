package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McpToolNaming} 单元测试：命名清洗、长度上限、冲突判定。
 */
class McpToolNamingTest {

    @Test
    @DisplayName("装配名：mcp_<server>_<tool>，两端小写化")
    void prefixedNameJoinsServerAndTool() {
        assertThat(McpToolNaming.prefixedName("filesystem", "read_file")).isEqualTo("mcp_filesystem_read_file");
    }

    @Test
    @DisplayName("清洗：大写、连字符、点等非法字符转下划线并折叠")
    void sanitizeReplacesInvalidChars() {
        assertThat(McpToolNaming.prefixedName("My-Server.v2", "Read-File")).isEqualTo("mcp_my_server_v2_read_file");
        assertThat(McpToolNaming.prefixedName("a___b", "c")).isEqualTo("mcp_a_b_c");
        assertThat(McpToolNaming.prefixedName("__x__", "y")).isEqualTo("mcp_x_y");
    }

    @Test
    @DisplayName("空名兜底为 unknown，不产出 mcp__tool 这种残缺名")
    void blankNamesFallBackToUnknown() {
        assertThat(McpToolNaming.prefixedName(null, "read_file")).isEqualTo("mcp_unknown_read_file");
        assertThat(McpToolNaming.prefixedName("fs", "  ")).isEqualTo("mcp_fs_unknown");
    }

    @Test
    @DisplayName("长度上限：超长名截断到 100 以内（审计列是 120）")
    void longNamesAreTruncated() {
        String longTool = "t".repeat(200);
        String name = McpToolNaming.prefixedName("server", longTool);
        assertThat(name.length()).isEqualTo(McpToolNaming.MAX_NAME_LENGTH);
        assertThat(name).startsWith("mcp_server_");
    }

    @Test
    @DisplayName("冲突判定：与内置工具同名、MCP 之间重名都能检出；无冲突返回空")
    void conflictsDetectsBothKinds() {
        List<String> withBuiltin = McpToolNaming.conflicts(
                Set.of("createTask", "mcp_filesystem_read_file"),
                List.of("mcp_filesystem_read_file", "mcp_filesystem_list_directory"));
        assertThat(withBuiltin).hasSize(1);
        assertThat(withBuiltin.get(0)).contains("与内置工具同名");

        List<String> intraMcp = McpToolNaming.conflicts(
                Set.of(),
                List.of("mcp_a_read_file", "mcp_b_read_file", "mcp_a_read_file"));
        assertThat(intraMcp).hasSize(1);
        assertThat(intraMcp.get(0)).contains("多个 MCP server 产生同名工具");

        assertThat(McpToolNaming.conflicts(Set.of("createTask"), List.of("mcp_fs_read_file"))).isEmpty();
        assertThat(McpToolNaming.conflicts(null, List.of("mcp_fs_read_file"))).isEmpty();
    }
}
