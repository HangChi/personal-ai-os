package mcp

import (
	"context"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestP7PersonalProtocolReadOnlyCatalogAndIndependentSwitch(t *testing.T) {
	o := PersonalOptions{Enabled: true, Name: "personal-ai-os", Version: "0.1.0", Endpoint: "/personal"}
	s := httptest.NewServer(NewPersonalServer(o, PersonalServices{}))
	defer s.Close()
	client := sdk.NewClient(&sdk.Implementation{Name: "fixture", Version: "1"}, nil)
	session, e := client.Connect(context.Background(), &sdk.StreamableClientTransport{Endpoint: s.URL + o.Endpoint, DisableStandaloneSSE: true}, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer session.Close()
	list, e := session.ListTools(context.Background(), nil)
	if e != nil || len(list.Tools) != 4 {
		t.Fatal(list, e)
	}
	for _, tool := range list.Tools {
		if !strings.HasPrefix(tool.Name, "personal_") || tool.Annotations == nil || !tool.Annotations.ReadOnlyHint || !tool.Annotations.IdempotentHint || *tool.Annotations.DestructiveHint || *tool.Annotations.OpenWorldHint {
			t.Fatal(tool)
		}
	}
	result, e := session.CallTool(context.Background(), &sdk.CallToolParams{Name: "personal_list_tasks", Arguments: map[string]any{"status": "invalid"}})
	if e != nil || !result.IsError {
		t.Fatal(result, e)
	}
	if _, e = session.CallTool(context.Background(), &sdk.CallToolParams{Name: "createTask"}); e == nil {
		t.Fatal("write tool exposed")
	}
	o.Enabled = false
	disabled := httptest.NewRecorder()
	NewPersonalServer(o, PersonalServices{}).ServeHTTP(disabled, httptest.NewRequest("POST", "/personal", strings.NewReader(`{}`)))
	if disabled.Code != 404 {
		t.Fatal(disabled.Code)
	}
	registry, _ := OpenRegistry(context.Background(), Options{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer registry.Close()
	h := (&HTTP{Registry: registry, Personal: o}).Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/mcp/servers", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"servers":[]`) {
		t.Fatal(w.Code, w.Body.String())
	}
}

func TestP7SummaryAndDisabledConfirmation(t *testing.T) {
	p := ToolCall{Server: "files", Tool: "write", Arguments: map[string]any{"z": 1, "b": "line\nline", "a": strings.Repeat("长", 81), "c": nil}}
	got := Summary(p)
	if !strings.HasPrefix(got, "MCP 工具调用确认：server=files，工具=write，a=") || !strings.HasSuffix(got, "，b=line line，c=null，…等 4 个参数") {
		t.Fatal(got)
	}
	if _, e := (&ConfirmationHandler{}).Execute(context.Background(), "MCP_TOOL_CALL", `{"server":"x","tool":"y"}`); e == nil {
		t.Fatal("disabled executed")
	}
}
