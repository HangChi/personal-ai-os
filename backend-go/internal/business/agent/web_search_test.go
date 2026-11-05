package agent

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestWebSearchUsesProviderExcerptAndSpecificURL(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("q") != "公开 资料" || r.Header.Get("X-Subscription-Token") != "test-key" {
			t.Error("unexpected provider request")
		}
		fmt.Fprint(w, `{"grounding":{"generic":[
			{"title":"页面","url":"https://example.com/reports/2026","snippets":["第一段","第二段"]},
			{"url":"javascript:alert(1)","snippets":["invalid"]},
			{"url":"https://example.com/no-excerpt","snippets":[]}
		]}}`)
	}))
	defer server.Close()

	tool := WebSearchTool(braveSearch("test-key", server.URL, server.Client()))
	turn := &Turn{AddWebSources: func(sources []WebSource) []WebSource {
		if len(sources) != 1 || sources[0].URL != "https://example.com/reports/2026" ||
			sources[0].Title != "页面" || sources[0].Content != "第一段\n\n第二段" {
			t.Fatal(sources)
		}
		sources[0].Index = 4
		return sources
	}}
	result, err := tool.Action(context.Background(), turn, map[string]any{"query": "公开 资料"})
	if err != nil || result.Status != "OK" || result.Data.([]WebSource)[0].Index != 4 {
		t.Fatal(result, err)
	}
}
