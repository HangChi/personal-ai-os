package model

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type boundaryTransport func(*http.Request) (*http.Response, error)

func (f boundaryTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type boundaryReader struct{}

func (boundaryReader) Read([]byte) (int, error) { return 0, errors.New("private read failure") }
func (boundaryReader) Close() error             { return nil }
func boundaryResponse(code int, body any) *http.Response {
	text, ok := body.(string)
	if !ok {
		b, _ := json.Marshal(body)
		text = string(b)
	}
	return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(text))}
}
func TestEmbeddingWireFailureBoundariesAndCacheRotation(t *testing.T) {
	resolved := resolver(t, nil, nil).Resolve(Values{BaseURL: ptr("https://example.test"), APIKey: ptr("synthetic"), ChatModel: ptr("chat"), EmbeddingModel: ptr("embed")})
	for _, mode := range []string{"json", "vectors", "status", "read", "network", "cancel", "body", "endpoint"} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			transport := boundaryTransport(func(*http.Request) (*http.Response, error) {
				switch mode {
				case "network":
					return nil, errors.New("private network failure")
				case "cancel":
					cancel()
					return nil, context.Canceled
				case "status":
					return boundaryResponse(503, "private upstream"), nil
				case "read":
					r := boundaryResponse(200, "")
					r.Body = boundaryReader{}
					return r, nil
				case "json":
					return boundaryResponse(200, "{"), nil
				default:
					return boundaryResponse(200, map[string]any{"data": []any{}}), nil
				}
			})
			client, e := NewEmbeddingClient(resolved, OpenAI, transport)
			if e != nil {
				t.Fatal(e)
			}
			request := EmbeddingRequest{Input: []string{"a"}}
			if mode == "body" {
				request.Options = map[string]any{"unencodable": make(chan int)}
			}
			if mode == "endpoint" {
				client.resolved.BaseURL = "http://bad host"
			}
			if _, e = client.Embed(ctx, request); e == nil {
				t.Fatal("invalid response accepted")
			}
			if strings.Contains(fmt.Sprintf("%#v", client), "synthetic") {
				t.Fatal("credentials exposed")
			}
		})
	}
	local := newLocal(1, time.Minute)
	local.put("a", []float32{1})
	local.put("a", []float32{2})
	if v, ok := local.get("a"); !ok || v[0] != 2 {
		t.Fatal(v, ok)
	}
	local.markSeen("b")
	if len(local.items) != 1 {
		t.Fatal("unbounded observations")
	}
	stats := NewCacheStats()
	now := time.Now()
	stats.now = func() time.Time { return now }
	stats.record(time.Second)
	now = now.Add(time.Minute)
	stats.record(2 * time.Second)
	now = now.Add(time.Minute)
	stats.record(3 * time.Second)
	if stats.Snapshot().Calls != 3 {
		t.Fatal("stats lost calls")
	}
	calls := 0
	cached, _ := NewCachedEmbedding(embeddingFunc(func(context.Context, EmbeddingRequest) (EmbeddingResponse, error) {
		calls++
		return EmbeddingResponse{}, nil
	}), "id", true, 1, time.Minute, nil)
	if _, e := cached.Embed(context.Background(), EmbeddingRequest{Options: map[string]any{"bad": make(chan int)}}); e != nil || calls != 1 || cached.Stats.Snapshot().Counts["bypass"] != 1 {
		t.Fatal(e, calls)
	}
	if fmt.Sprintf("%#v", cached) != "CachedEmbedding{redacted}" {
		t.Fatal("cache disclosure")
	}
	if _, e := NewRedisEmbeddingCache(nil, "id", time.Second); e == nil {
		t.Fatal("nil Redis")
	}
}
func TestOllamaRequestAndStreamFailureBoundaries(t *testing.T) {
	resolved := resolver(t, nil, nil).Resolve(Values{BaseURL: ptr("https://example.test"), APIKey: ptr("synthetic"), ChatModel: ptr("chat")})
	transport := boundaryTransport(func(*http.Request) (*http.Response, error) {
		return boundaryResponse(200, map[string]any{"message": map[string]any{"content": "ok"}, "done": true}), nil
	})
	client, e := NewChatClient(resolved, Ollama, transport)
	if e != nil {
		t.Fatal(e)
	}
	for _, arguments := range []string{"{}", "{"} {
		req := ChatRequest{Temperature: ptr(Temperature(.2)), MaxTokens: ptr(9), Messages: []Message{{Role: "assistant", ToolCalls: []ToolCall{{Function: ToolFunction{Name: "lookup", Arguments: arguments}}}}}}
		_, e := client.Call(context.Background(), req)
		if (e != nil) != (arguments == "{") {
			t.Fatal(e)
		}
	}
	if _, e := client.Call(context.Background(), ChatRequest{Messages: []Message{{Role: "user", Images: []Image{{URL: "https://example.test/image"}}}}}); e == nil {
		t.Fatal("external Ollama image")
	}
	for _, mode := range []string{"network", "read", "decode", "body", "endpoint", "stream", "stream-tail"} {
		t.Run(mode, func(t *testing.T) {
			client, clientErr := NewChatClient(resolved, OpenAI, boundaryTransport(func(*http.Request) (*http.Response, error) {
				if mode == "network" {
					return nil, errors.New("private network")
				}
				if mode == "read" {
					r := boundaryResponse(200, "")
					r.Body = boundaryReader{}
					return r, nil
				}
				body := "{"
				if mode == "stream-tail" {
					body = "data: {"
				}
				return boundaryResponse(200, body), nil
			}))
			if clientErr != nil {
				t.Fatal(clientErr)
			}
			request := ChatRequest{}
			if mode == "body" {
				request.ExtraBody = map[string]any{"bad": make(chan int)}
			}
			if mode == "endpoint" {
				client.resolved.BaseURL = "http://bad host"
			}
			var e error
			if strings.HasPrefix(mode, "stream") {
				_, e = client.Stream(context.Background(), request, nil)
			} else {
				_, e = client.Call(context.Background(), request)
			}
			if e == nil {
				t.Fatal("failure swallowed")
			}
			b, _ := json.Marshal(client)
			if string(b) != "{"+string(rune(34))+"redacted"+string(rune(34))+":true}" {
				t.Fatal(string(b))
			}
			_ = fmt.Sprintf("%#v", client)
		})
	}
	rpc := &UpstreamError{Status: 503, body: "private secret"}
	b, _ := json.Marshal(rpc)
	if strings.Contains(string(b)+fmt.Sprintf("%#v", rpc), "private") {
		t.Fatal("upstream disclosure")
	}
}
func TestProbeNetworkMalformedListAndReferenceFallbacks(t *testing.T) {
	target := ProbeTarget{OpenAI, "https://example.test", "synthetic"}
	for _, failure := range []error{errors.New("private network"), context.Canceled, context.DeadlineExceeded} {
		probe := NewProbe(boundaryTransport(func(*http.Request) (*http.Response, error) { return nil, failure }))
		result := probe.Models(context.Background(), target, nil)
		if result.OK || result.Error == nil {
			t.Fatal(result)
		}
		if _, e := probe.Window(context.Background(), target, ""); e == nil {
			t.Fatal("blank model")
		}
		if _, e := probe.Window(context.Background(), ProbeTarget{}, "a"); e == nil {
			t.Fatal("blank endpoint")
		}
	}
	for _, name := range []string{"qwen3-max", "qwen-plus", "unknown"} {
		for _, list := range []any{map[string]any{"models": []any{map[string]any{"name": name, "context_length": 8192}}}, map[string]any{"other": true}, []any{}} {
			p := NewProbe(boundaryTransport(func(r *http.Request) (*http.Response, error) {
				if strings.HasSuffix(r.URL.Path, "/models") {
					return boundaryResponse(200, list), nil
				}
				return boundaryResponse(200, map[string]any{}), nil
			}))
			got, e := p.Window(context.Background(), target, name)
			if e != nil {
				t.Fatal(e)
			}
			if got.OK && got.Window == nil {
				t.Fatal(got)
			}
		}
	}
	names := make([]any, 501)
	for i := range names {
		names[i] = map[string]any{"id": fmt.Sprint(i), "context_length": 8192}
	}
	p := NewProbe(boundaryTransport(func(*http.Request) (*http.Response, error) {
		return boundaryResponse(200, map[string]any{"data": names}), nil
	}))
	if result := p.Models(context.Background(), target, nil); !result.OK || len(result.Models) != 500 {
		t.Fatal(len(result.Models), result.Error)
	}
	for _, kind := range []string{"MODEL_NOT_LISTED", "TIMEOUT", "NETWORK_ERROR"} {
		if failureMessage(kind, nil) == "" {
			t.Fatal(kind)
		}
	}
	_ = fmt.Sprintf("%#v", target)
	json.Marshal(target)
	if _, _, e := p.fetch(context.Background(), target, "http://bad host", "GET", nil, time.Second); e == nil {
		t.Fatal("invalid endpoint")
	}
	if safeBody([]byte("ordinary"), "synthetic") != "ordinary" {
		t.Fatal("ordinary detail changed")
	}
}
