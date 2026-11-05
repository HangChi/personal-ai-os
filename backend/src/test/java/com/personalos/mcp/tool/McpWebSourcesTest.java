package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.WebSource;
import com.personalos.common.json.JsonCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpWebSourcesTest {
    private final JsonCodec json = new JsonCodec(new ObjectMapper());

    @Test
    void readsMatchingFetchAndStructuredSearchSources() {
        String fetched = """
                {"content":[{"type":"text","text":"Contents of https://example.org/article:\\n文章摘录\\n\\n<error>Content truncated. Call again.</error>"}]}
                """;
        assertThat(McpWebSources.extract("fetch", Map.of("url", "https://example.org/article"), fetched, json))
                .extracting(WebSource::content).containsExactly("文章摘录");
        assertThat(McpWebSources.extract("fetch", Map.of("url", "https://different.example/article"), fetched, json)).isEmpty();
        String search = """
                {"structuredContent":{"results":[
                  {"url":"https://example.org/report","title":"报告","snippet":"网页摘录"},
                  {"url":"javascript:alert(1)","snippet":"unsafe"}
                ]}}
                """;
        assertThat(McpWebSources.extract("web_search", Map.of(), search, json))
                .extracting(WebSource::url).containsExactly("https://example.org/report");
        assertThat(McpWebSources.extract("list_tasks", Map.of(), search, json)).isEmpty();
    }
}
