package com.personalos.mcp.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McpConfirmationSummary} 单元测试：服务端摘要的确定性与截断。
 */
class McpConfirmationSummaryTest {

    @Test
    @DisplayName("摘要含 server、工具与关键参数；参数按键排序保证确定性")
    void summaryIsDeterministic() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("content", "今天天气不错");
        arguments.put("path", "./notes.md");

        String summary = McpConfirmationSummary.summarize(new McpToolCall("filesystem", "write_file", arguments));

        assertThat(summary).isEqualTo(
                "MCP 工具调用确认：server=filesystem，工具=write_file，content=今天天气不错，path=./notes.md");
        // 键序颠倒得到同样摘要——顺序不稳定的摘要会让用户对不上号
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("path", "./notes.md");
        reversed.put("content", "今天天气不错");
        assertThat(McpConfirmationSummary.summarize(new McpToolCall("filesystem", "write_file", reversed)))
                .isEqualTo(summary);
    }

    @Test
    @DisplayName("最多展示 3 个参数，超出以「等 N 个参数」收尾")
    void atMostThreeArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("a", "1");
        arguments.put("b", "2");
        arguments.put("c", "3");
        arguments.put("d", "4");

        String summary = McpConfirmationSummary.summarize(new McpToolCall("s", "t", arguments));

        assertThat(summary).contains("a=1").contains("b=2").contains("c=3").contains("等 4 个参数");
        assertThat(summary).doesNotContain("d=4");
    }

    @Test
    @DisplayName("长值截断到 80 字符，换行折叠为空格")
    void longValuesAreTruncated() {
        String longValue = "x".repeat(200);
        String summary = McpConfirmationSummary.summarize(
                new McpToolCall("s", "t", Map.of("text", longValue)));

        assertThat(summary).contains("text=" + "x".repeat(80) + "…");
        assertThat(summary.length()).isLessThan(200);

        String multiline = McpConfirmationSummary.summarize(
                new McpToolCall("s", "t", Map.of("text", "a\nb")));
        assertThat(multiline).contains("text=a b").doesNotContain("\n");
    }

    @Test
    @DisplayName("无参数时不画蛇添足")
    void noArgumentsMeansNoArgsSection() {
        assertThat(McpConfirmationSummary.summarize(new McpToolCall("s", "t", Map.of())))
                .isEqualTo("MCP 工具调用确认：server=s，工具=t");
        assertThat(McpConfirmationSummary.summarize(new McpToolCall("s", "t", null)))
                .isEqualTo("MCP 工具调用确认：server=s，工具=t");
    }
}
