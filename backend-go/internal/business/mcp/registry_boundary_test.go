package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)
import sdk "github.com/modelcontextprotocol/go-sdk/mcp"

func TestRegistryRejectsInvalidTransportConfiguration(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx := context.Background()
	for _, endpoint := range []string{"%", "ftp://example.test", "https:///no-host", "https://u:p@example.test", "https://example.test/#fragment"} {
		if r, e := OpenRegistry(ctx, Options{Enabled: true, Servers: map[string]ServerConfig{"remote": {Endpoint: endpoint}}}, logger); e == nil || r != nil {
			t.Fatal(endpoint, e)
		}
	}
	if _, e := OpenRegistry(nil, Options{}, logger); e == nil {
		t.Fatal("nil context")
	}
	if _, e := OpenRegistry(ctx, Options{}, nil); e == nil {
		t.Fatal("nil logger")
	}
	if _, e := OpenRegistry(ctx, Options{Enabled: true, Servers: map[string]ServerConfig{"both": {Endpoint: "https://example.test", Command: "bad"}}}, logger); e == nil {
		t.Fatal("ambiguous transport")
	}
	disabled, _ := OpenRegistry(ctx, Options{}, logger)
	defer disabled.Close()
	if disabled.Enabled() {
		t.Fatal("enabled")
	}
	if e := disabled.Reconnect(ctx, "x"); e == nil {
		t.Fatal("disabled reconnect")
	}
	if disabled.session("x") != nil {
		t.Fatal("invented session")
	}
	c, cancel := context.WithCancel(ctx)
	cancel()
	if r, e := OpenRegistry(c, Options{Enabled: true, Servers: map[string]ServerConfig{"x": {Endpoint: "http://127.0.0.1:1"}}}, logger); e == nil || r != nil {
		t.Fatal("cancelled context")
	}
	for _, v := range []any{ServerConfig{Env: map[string]string{"secret": "synthetic-private"}}, Options{Servers: map[string]ServerConfig{"private": {Command: "synthetic-private"}}}, disabled} {
		if strings.Contains(fmt.Sprintf("%v %#v", v, v), "synthetic-private") {
			t.Fatal("leak")
		}
	}
	b, e := json.Marshal(ServerConfig{})
	var redacted map[string]bool
	if e != nil || json.Unmarshal(b, &redacted) != nil || !redacted["redacted"] {
		t.Fatal(string(b), e)
	}
}
func TestRegistryRejectsUnboundedOrBrokenToolPagination(t *testing.T) {
	for _, kind := range []string{"cycle", "never-ending", "oversized", "list-error", "disconnect"} {
		t.Run(kind, func(t *testing.T) {
			var pages atomic.Int32
			var disconnected atomic.Bool
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if disconnected.Load() {
					w.WriteHeader(503)
					return
				}
				var request struct {
					ID     json.RawMessage
					Method string
				}
				if json.NewDecoder(r.Body).Decode(&request) != nil {
					w.WriteHeader(400)
					return
				}
				w.Header().Set("Content-Type", "application/json")
				var result any
				switch request.Method {
				case "initialize":
					result = map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "fixture", "version": "1"}}
				case "tools/list":
					n := pages.Add(1)
					if kind == "list-error" {
						_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": request.ID, "error": map[string]any{"code": -32603, "message": "private list error"}})
						return
					}
					tools := []any{}
					cursor := ""
					if kind == "oversized" {
						for i := 0; i < 4097; i++ {
							tools = append(tools, map[string]any{"name": fmt.Sprint("tool", i), "inputSchema": map[string]any{"type": "object"}})
						}
					}
					if kind == "cycle" {
						cursor = "repeat"
					}
					if kind == "never-ending" {
						cursor = fmt.Sprint(n)
					}
					result = map[string]any{"tools": tools, "nextCursor": cursor}
				default:
					w.WriteHeader(202)
					return
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": request.ID, "result": result})
			}))
			defer server.Close()
			registry, e := OpenRegistry(context.Background(), Options{Enabled: true, HTTPClient: server.Client(), Servers: map[string]ServerConfig{"test": {Endpoint: server.URL, Timeout: 3 * time.Second}}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if e != nil {
				t.Fatal(e)
			}
			defer registry.Close()
			if kind != "disconnect" {
				if registry.Statuses()[0].Available || registry.Statuses()[0].ToolCount != 0 {
					t.Fatal(registry.Statuses())
				}
				if kind == "never-ending" && pages.Load() != 128 {
					t.Fatal(pages.Load())
				}
				return
			}
			if !registry.Statuses()[0].Available {
				t.Fatal(registry.Statuses())
			}
			failed := registry.session("test")
			registry.markUnavailable("absent", failed)
			registry.markUnavailable("test", nil)
			disconnected.Store(true)
			if _, e = registry.Call(context.Background(), "test", "read", nil); e == nil {
				t.Fatal("transport failure lost")
			}
			if !registry.Statuses()[0].Available {
				t.Fatal("HTTP JSON-RPC rejection must preserve session")
			}
			_ = failed.Close()
			if _, e = registry.Call(context.Background(), "test", "read", nil); e == nil {
				t.Fatal("closed transport accepted")
			}
			if registry.Statuses()[0].Available {
				t.Fatal("broken session retained")
			}
		})
	}
}
func TestRegistryCatalogDropsMalformedSchemas(t *testing.T) {
	r, _ := OpenRegistry(context.Background(), Options{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer r.Close()
	r.servers["test"] = &serverSlot{tools: []*sdk.Tool{nil, {Name: "bad", InputSchema: make(chan int)}, {Name: "valid", InputSchema: map[string]any{"type": "object"}}}}
	if got := r.Catalog(); len(got) != 1 || got[0].Original != "valid" {
		t.Fatal(got)
	}
	if e := r.Reconnect(context.Background(), "absent"); e == nil {
		t.Fatal("unknown server")
	}
	r.enabled = true
	if e := r.Reconnect(context.Background(), "absent"); e == nil {
		t.Fatal("unknown enabled server")
	}
}
