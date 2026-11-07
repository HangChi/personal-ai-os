package agent

import (
	"encoding/json"
	"fmt"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"testing"
)

func TestMcpSourceTextFallbackAndBounds(t *testing.T) {
	for _, result := range []*sdk.CallToolResult{nil, {IsError: true}} {
		if sources := mcpWebSources("fetch", nil, result); len(sources) != 0 {
			t.Fatal(sources)
		}
	}
	fetched := &sdk.CallToolResult{Content: []sdk.Content{&sdk.ImageContent{}, &sdk.TextContent{Text: "Contents of https://example.test/:\n<error>not available"}}}
	if sources := mcpWebSources("fetch", map[string]any{"url": "https://example.test"}, fetched); len(sources) != 0 {
		t.Fatal(sources)
	}
	rows := []map[string]string{}
	for i := 0; i < 7; i++ {
		rows = append(rows, map[string]string{"title": fmt.Sprintf("Source %d", i), "url": fmt.Sprintf("https://example.test/%d", i), "description": "Synthetic source evidence"})
	}
	raw, err := json.Marshal(map[string]any{"results": rows})
	if err != nil {
		t.Fatal(err)
	}
	result := &sdk.CallToolResult{Content: []sdk.Content{&sdk.ImageContent{}, &sdk.TextContent{Text: "invalid JSON"}, &sdk.TextContent{Text: string(raw)}}}
	if sources := mcpWebSources("web_search", nil, result); len(sources) != 5 {
		t.Fatalf("sources=%v", sources)
	}
}
