package chatcontext

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

func TestContextGuardNetworkRetryBoundariesAndEvidence(t *testing.T) {
	for _, mode := range []string{"retry", "authentication", "partial", "bounded", "cancelled", "oversized", "model-budget"} {
		t.Run(mode, func(t *testing.T) {
			var calls atomic.Int32
			var sizes []int
			var sizesMu sync.Mutex
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				call := calls.Add(1)
				var body struct{ Messages []model.Message }
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
				}
				sizesMu.Lock()
				sizes = append(sizes, len(body.Messages))
				sizesMu.Unlock()
				if mode == "authentication" {
					w.WriteHeader(401)
					fmt.Fprint(w, `{"error":{"message":"unauthorized"}}`)
					return
				}
				if mode == "partial" {
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"context_length_exceeded\"}}\n\n")
					return
				}
				if mode == "bounded" || call == 1 {
					w.WriteHeader(400)
					fmt.Fprint(w, `{"error":{"message":"context_length_exceeded"}}`)
					return
				}
				w.Header().Set("Content-Type", "text/event-stream")
				fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n")
			}))
			defer server.Close()
			props, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.openai.base-url": server.URL, "spring.ai.openai.api-key": "synthetic-context-guard", "spring.ai.openai.chat.model": "fixture-guard"}})
			if err != nil {
				t.Fatal(err)
			}
			resolver, err := model.NewResolver(props)
			if err != nil {
				t.Fatal(err)
			}
			client, err := model.NewChatClient(resolver.Resolve(model.Values{}), model.OpenAI, server.Client().Transport)
			if err != nil {
				t.Fatal(err)
			}
			b, err := NewBudget(context.Background(), config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 8192}}, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			request := model.ChatRequest{Messages: []model.Message{{Role: "system", Content: "system"}, {Role: "user", Content: strings.Repeat("old ", 600)}, {Role: "assistant", Content: "old response"}, {Role: "user", Content: "current"}}}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if mode == "cancelled" {
				cancel()
			}
			if mode == "oversized" {
				request.Messages[len(request.Messages)-1].Content = strings.Repeat("current ", 20000)
			}
			budgetErr := errors.New("synthetic model budget exhausted")
			before := func() error {
				if mode == "model-budget" {
					return budgetErr
				}
				return nil
			}
			var received string
			result, err := b.GuardStream(ctx, client, request, b.Evidence.Generation(), before, func(c model.ChatChunk) error { received += c.Content; return nil })
			sizesMu.Lock()
			defer sizesMu.Unlock()
			switch mode {
			case "retry":
				if err != nil || result.Content != "ok" || received != "ok" || calls.Load() != 2 || len(sizes) != 2 || sizes[1] >= sizes[0] {
					t.Fatal(result, err, calls.Load(), sizes)
				}
				if b.Evidence.Snapshot(b.Evidence.Generation(), client.Name()).VerifiedInputTokens != 1200 {
					t.Fatal("successful usage evidence lost")
				}
			case "cancelled", "oversized", "model-budget":
				if err == nil || calls.Load() != 0 {
					t.Fatal(err, calls.Load())
				}
				if mode == "model-budget" && !errors.Is(err, budgetErr) {
					t.Fatal(err)
				}
			case "bounded":
				if err == nil || calls.Load() < 1 || calls.Load() > int32(b.Protection.MaxRetries+1) {
					t.Fatal(err, calls.Load())
				}
			default:
				if err == nil || calls.Load() != 1 {
					t.Fatal(err, calls.Load())
				}
				if mode == "partial" && received != "partial" {
					t.Fatal(received)
				}
			}
			if mode != "retry" && b.Evidence.Snapshot(b.Evidence.Generation(), client.Name()).VerifiedInputTokens != 0 {
				t.Fatal("failed stream recorded success evidence")
			}
			if request.Messages[1].Content != strings.Repeat("old ", 600) {
				t.Fatal("original history mutated")
			}
		})
	}
}

func TestContextGuardReservesImagesSchemasAndThinking(t *testing.T) {
	b, err := NewBudget(context.Background(), config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 32768}}, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	request := model.ChatRequest{Messages: []model.Message{{Role: "system", Content: "system"}, {Role: "user", Content: "current", Images: []model.Image{{MIME: "image/png", Data: []byte{1}}}}}, Tools: []model.Tool{{Name: "fixture", Description: "tool", Parameters: map[string]any{"type": "object", "properties": map[string]any{"value": map[string]any{"type": "string"}}}}}, ExtraBody: map[string]any{"thinking_budget": 8192}}
	prepared, err := b.Prepare(context.Background(), request, "fixture", b.Evidence.Generation(), 0, 0)
	if err != nil || prepared.MaxTokens == nil || *prepared.MaxTokens < 9216 {
		t.Fatal(prepared, err)
	}
	without := request
	without.Messages = append([]model.Message(nil), request.Messages...)
	without.Messages[1].Images = nil
	without.Tools = nil
	if b.Count(request, "fixture", 0) <= b.Count(without, "fixture", 0)+b.Protection.ImageReserveTokens {
		t.Fatal("image/schema reservations missing")
	}
	b.AI.Memory.ContextWindowTokens = 8192
	if _, err = b.Prepare(context.Background(), request, "fixture", 0, 0, 0); err == nil {
		t.Fatal("thinking reserve exceeds window")
	}
}
