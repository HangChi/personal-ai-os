package model

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func clientFor(t *testing.T, server *httptest.Server, p Protocol) *ChatClient {
	t.Helper()
	v := resolver(t, nil, nil).Resolve(Values{BaseURL: ptr(server.URL), APIKey: ptr("synthetic-protocol-only-key"), ChatModel: ptr("fixture-chat")})
	c, e := NewChatClient(v, p, server.Client().Transport)
	if e != nil {
		t.Fatal(e)
	}
	return c
}
func TestOpenAIWireImagesToolsExtraUsageAndStreaming(t *testing.T) {
	var calls atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Method != "POST" || r.Header.Get("Authorization") != "Bearer synthetic-protocol-only-key" {
			t.Error("endpoint/auth mismatch")
		}
		var body map[string]any
		if e := json.NewDecoder(r.Body).Decode(&body); e != nil {
			t.Error(e)
		}
		if body["model"] != "fixture-chat" || body["enable_thinking"] != false || body["thinking_budget"] != float64(2048) {
			t.Error("model/extra mismatch")
		}
		messages := body["messages"].([]any)
		content := messages[0].(map[string]any)["content"].([]any)
		if content[1].(map[string]any)["image_url"].(map[string]any)["url"] != "data:image/png;base64,AQID" {
			t.Error("image mapping mismatch")
		}
		tools := body["tools"].([]any)
		if tools[0].(map[string]any)["function"].(map[string]any)["parameters"].(map[string]any)["type"] != "object" {
			t.Error("schema missing")
		}
		if body["stream"] == true {
			if body["stream_options"].(map[string]any)["include_usage"] != true {
				t.Error("usage not requested")
			}
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"正常\",\"tool_calls\":[{\"index\":0,\"id\":\"tc1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\"}}]}}]}\n\n")
			fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"} \"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
			fmt.Fprint(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}\n\ndata: [DONE]\n\n")
			return
		}
		fmt.Fprint(w, `{"id":"c1","model":"fixture-chat","choices":[{"message":{"content":"正常","tool_calls":[{"id":"tc1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}`)
	}))
	defer s.Close()
	c := clientFor(t, s, OpenAI)
	c.extra = map[string]any{"enable_thinking": "false"}
	req := ChatRequest{Messages: []Message{{Role: "user", Content: "看图", Images: []Image{{MIME: "image/png", Data: []byte{1, 2, 3}}}}}, Tools: []Tool{{Name: "lookup", Parameters: map[string]any{"type": "object"}}}, ExtraBody: map[string]any{"enable_thinking": false, "thinking_budget": 2048}}
	out, e := c.Call(context.Background(), req)
	if e != nil || out.Content != "正常" || out.Usage.TotalTokens != 15 || len(out.ToolCalls) != 1 {
		t.Fatalf("call result=%+v %v", out, e)
	}
	chunks := 0
	out, e = c.Stream(context.Background(), req, func(ch ChatChunk) error { chunks++; return nil })
	if e != nil || out.Usage.TotalTokens != 15 || out.ToolCalls[0].Function.Arguments != "{} " || chunks != 3 {
		t.Fatalf("stream=%+v chunks=%d err=%v", out, chunks, e)
	}
	if calls.Load() != 2 {
		t.Fatal("unexpected retry")
	}
}
func TestOllamaNativeChatAndEmbedding(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		switch r.URL.Path {
		case "/api/chat":
			images := body["messages"].([]any)[0].(map[string]any)["images"].([]any)
			if images[0] != "AQID" {
				t.Error("ollama images mismatch")
			}
			if body["stream"] == true {
				fmt.Fprintln(w, `{"message":{"content":"正"},"done":false}`)
				fmt.Fprintln(w, `{"message":{"content":"常","tool_calls":[{"function":{"name":"look","arguments":{"id":1}}}]},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}`)
			} else {
				fmt.Fprint(w, `{"model":"fixture-chat","message":{"content":"正常"},"done":true,"prompt_eval_count":5,"eval_count":2}`)
			}
		case "/api/embed":
			fmt.Fprint(w, `{"model":"fixture-embed","embeddings":[[1,2],[3,4]],"prompt_eval_count":9}`)
		default:
			t.Error("unexpected path", r.URL.Path)
		}
	}))
	defer s.Close()
	c := clientFor(t, s, Ollama)
	req := ChatRequest{Messages: []Message{{Role: "user", Images: []Image{{Data: []byte{1, 2, 3}}}}}}
	out, e := c.Call(context.Background(), req)
	if e != nil || out.Content != "正常" || out.Usage.TotalTokens != 7 {
		t.Fatal(out, e)
	}
	out, e = c.Stream(context.Background(), req, nil)
	if e != nil || out.Content != "正常" || len(out.ToolCalls) != 1 || out.ToolCalls[0].Function.Arguments != `{"id":1}` {
		t.Fatal(out, e)
	}
	embedding, e := NewEmbeddingClient(c.resolved, Ollama, s.Client().Transport)
	if e != nil {
		t.Fatal(e)
	}
	v, e := embedding.Embed(context.Background(), EmbeddingRequest{Input: []string{"a", "b"}})
	if e != nil || v.Usage.PromptTokens != 9 || v.Results[1].Vector[1] != 4 {
		t.Fatal(v, e)
	}
}
func TestProtocolNoRetryRedirectCancellationAndTruncatedStream(t *testing.T) {
	var calls atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch r.URL.Query().Get("case") {
		default:
			if r.Header.Get("X-case") == "" {
			}
		}
		w.WriteHeader(429)
		fmt.Fprint(w, `{"error":"synthetic-protocol-only-key too many input tokens max_input_tokens exceeded 8192"}`)
	}))
	defer s.Close()
	c := clientFor(t, s, OpenAI)
	_, e := c.Call(context.Background(), ChatRequest{})
	var up *UpstreamError
	if !errors.As(e, &up) || up.Status != 429 || calls.Load() != 1 || strings.Contains(e.Error(), "synthetic") {
		t.Fatal("retry/error leak", e)
	}
	if !IsOverflow(e) {
		t.Fatal("lost internal window evidence")
	}
	for _, test := range []string{"cancel", "truncated", "redirect"} {
		t.Run(test, func(t *testing.T) {
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch test {
				case "cancel":
					_, _ = io.Copy(io.Discard, r.Body)
					<-r.Context().Done()
				case "truncated":
					fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
				case "redirect":
					http.Redirect(w, r, "http://example.invalid", 302)
				}
			}))
			defer s.Close()
			c := clientFor(t, s, OpenAI)
			ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
			defer cancel()
			_, e := c.Stream(ctx, ChatRequest{}, nil)
			if e == nil {
				t.Fatal("failure not surfaced")
			}
			if test == "cancel" && !errors.Is(e, context.DeadlineExceeded) {
				t.Fatal(e)
			}
		})
	}
}
func TestProtocolMalformedBodyAndCallbackFailure(t *testing.T) {
	for _, body := range []string{`{}`, `{"error":{"message":"bad"}}`, `{"choices":[{"delta":{"content":[]}}]}`, `{"choices":[{"delta":{"tool_calls":[{"index":-1}]}}]}`} {
		if _, _, _, err := decodeChat([]byte(body), OpenAI, true); err == nil {
			t.Fatal("accepted malformed response", body)
		}
	}
	_ = io.Discard
}
