package agent

import (
	"testing"

	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestMCPWebSourcesUseOnlySuccessfulMatchingPageAndStructuredSearch(t *testing.T) {
	args := map[string]any{"url": "https://example.org/article"}
	result := &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: "Contents of https://example.org/article:\n文章摘录\n\n<error>Content truncated. Call again.</error>"}}}
	sources := mcpWebSources("fetch", args, result)
	if len(sources) != 1 || sources[0].Content != "文章摘录" || sources[0].URL != args["url"] {
		t.Fatal(sources)
	}
	if got := mcpWebSources("fetch", map[string]any{"url": "https://different.example/article"}, result); len(got) != 0 {
		t.Fatal(got)
	}
	result = &sdk.CallToolResult{StructuredContent: map[string]any{"results": []map[string]any{
		{"title": "报告", "url": "https://example.org/report", "snippet": "网页摘录"},
		{"url": "javascript:alert(1)", "snippet": "unsafe"},
	}}}
	sources = mcpWebSources("web_search", nil, result)
	if len(sources) != 1 || sources[0].URL != "https://example.org/report" || len(mcpWebSources("list_tasks", nil, result)) != 0 {
		t.Fatal(sources)
	}
}
