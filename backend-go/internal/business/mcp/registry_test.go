package mcp

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

func testServer() *sdk.Server {
	s := sdk.NewServer(&sdk.Implementation{Name: "fixture", Version: "1"}, nil)
	s.AddTool(&sdk.Tool{Name: "echo", Description: "Echo fixture", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"text": map[string]any{"type": "string"}}}}, func(ctx context.Context, req *sdk.CallToolRequest) (*sdk.CallToolResult, error) {
		var args map[string]any
		_ = json.Unmarshal(req.Params.Arguments, &args)
		if args["text"] == "block" {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(300 * time.Millisecond):
				return &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: "finished"}}}, nil
			}
		}
		return &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: "echo:" + strings.TrimSpace(strings.Trim(string(req.Params.Arguments), "{}"))}}, IsError: args["text"] == "error"}, nil
	})
	return s
}

func TestP7StdioHelper(t *testing.T) {
	if os.Getenv("PERSONALOS_P7_HELPER") != "true" {
		t.Skip("child process only")
	}
	_ = testServer().Run(context.Background(), &sdk.StdioTransport{})
	os.Exit(0)
}

func TestP7NamesPolicyAndSchema(t *testing.T) {
	for _, tc := range [][3]string{{"My-Server.v2", "Read-File", "mcp_my_server_v2_read_file"}, {"a___b", "c", "mcp_a_b_c"}, {"", " ", "mcp_unknown_unknown"}} {
		if got := PrefixedName(tc[0], tc[1]); got != tc[2] {
			t.Fatal(got, tc)
		}
	}
	if len(PrefixedName("server", strings.Repeat("x", 200))) != 100 || Policy("write", []string{"read"}) != "CONFIRM" || Policy("read", []string{"read"}) != "AUTO" {
		t.Fatal("name length or safe default")
	}
	if len(Conflicts([]string{"mcp_a_t"}, []string{"mcp_a_t", "mcp_b_t", "mcp_b_t"})) != 2 {
		t.Fatal("missing name conflicts")
	}
	if _, ok := NormalizeSchema(map[string]any{"properties": map[string]any{}}); !ok {
		t.Fatal("Spring normalized object schema lost")
	}
	if _, ok := NormalizeSchema(map[string]any{"type": "array"}); ok {
		t.Fatal("bad schema accepted")
	}
}

func TestP7RealStdioLifecycleAndFailureIsolation(t *testing.T) {
	bin, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	r, err := OpenRegistry(context.Background(), Options{Enabled: true, Name: "personal-ai-os", Version: "fixture", Servers: map[string]ServerConfig{
		"good": {Command: bin, Args: []string{"-test.run=^TestP7StdioHelper$"}, Env: map[string]string{"PERSONALOS_P7_HELPER": "true"}, Timeout: 3 * time.Second},
		"bad":  {Command: "/nonexistent/p7-fixture", Timeout: time.Second},
	}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(r.Close)
	statuses := r.Statuses()
	if len(statuses) != 2 || statuses[0].Available || !statuses[1].Available || statuses[1].ToolCount != 1 {
		t.Fatal(statuses)
	}
	if _, err = r.Call(context.Background(), "good", "echo", map[string]any{"text": "ok"}); err != nil {
		t.Fatal(err)
	}
	old := r.session("good")
	if err = r.Reconnect(context.Background(), "good"); err != nil {
		t.Fatal(err)
	}
	r.markUnavailable("good", old)
	if !r.Statuses()[1].Available {
		t.Fatal("old failure closed replacement client")
	}
	r.Close()
	if _, err = r.Call(context.Background(), "good", "echo", nil); err == nil {
		t.Fatal("closed client called")
	}
	if err = r.Reconnect(context.Background(), "good"); err == nil {
		t.Fatal("registry resurrected after close")
	}
}

func TestP7HTTPTimeoutBusinessErrorAndReconnect(t *testing.T) {
	server := testServer()
	h := sdk.NewStreamableHTTPHandler(func(*http.Request) *sdk.Server { return server }, &sdk.StreamableHTTPOptions{Stateless: true, JSONResponse: true})
	s := httptest.NewServer(h)
	defer s.Close()
	r, err := OpenRegistry(context.Background(), Options{Enabled: true, Name: "test", Version: "1", Servers: map[string]ServerConfig{"http": {Endpoint: s.URL, Timeout: time.Second}}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	result, err := r.Call(context.Background(), "http", "echo", map[string]any{"text": "error"})
	if err != nil || !result.IsError || !r.Statuses()[0].Available {
		t.Fatal(result, err, r.Statuses())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err = r.Call(ctx, "http", "echo", map[string]any{"text": "block"}); err == nil {
		t.Fatal("deadline ignored")
	}
	if err = r.Reconnect(context.Background(), "http"); err != nil || !r.Statuses()[0].Available {
		t.Fatal(err, r.Statuses())
	}
}

func TestP7ConfigurationFailureAndDisabledNoSideEffects(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if _, err := OpenRegistry(context.Background(), Options{Enabled: true, Servers: map[string]ServerConfig{"oops": {}}}, logger); err == nil {
		t.Fatal("missing command accepted")
	}
	r, err := OpenRegistry(context.Background(), Options{Enabled: false, Servers: map[string]ServerConfig{"oops": {}}}, logger)
	if err != nil || len(r.Statuses()) != 0 {
		t.Fatal(err)
	}
	r.Close()
}
