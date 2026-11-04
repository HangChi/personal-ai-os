package knowledge

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func gatewayRuntime(t *testing.T, s *httptest.Server) *model.Runtime {
	t.Helper()
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.openai.base-url": s.URL, "spring.ai.openai.api-key": "synthetic-gateway", "spring.ai.openai.chat.model": "fixture"}})
	if e != nil {
		t.Fatal(e)
	}
	resolver, e := model.NewResolver(p)
	if e != nil {
		t.Fatal(e)
	}
	r := model.NewRuntime(s.Client().Transport)
	if e = r.Apply(resolver.Resolve(model.Values{})); e != nil {
		t.Fatal(e)
	}
	return r
}
func TestGatewayFencesOversizedJSONBudgetAndVisionPlainClient(t *testing.T) {
	var calls atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		if e := json.NewDecoder(r.Body).Decode(&payload); e != nil {
			t.Error(e)
		}
		if _, ok := payload["tools"]; ok {
			t.Error("auxiliary model received tools")
		}
		text := "```json\n{\"queries\":[]}\n```"
		switch calls.Add(1) {
		case 2:
			text = strings.Repeat("x", 64001)
		case 3:
			text = "not json"
		case 4:
			raw, _ := json.Marshal(payload)
			if !strings.Contains(string(raw), "data:image/png;base64,AQID") {
				t.Error("vision MIME/data absent", string(raw))
			}
			text = "图片转录文字"
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": text}}}})
	}))
	defer s.Close()
	runtime := gatewayRuntime(t, s)
	g := NewGateway(context.Background(), runtime, NewStats())
	defer g.Close()
	raw, e := g.Call(context.Background(), "rules", map[string]any{}, time.Second)
	if e != nil || string(raw) != `{"queries":[]}` {
		t.Fatal(string(raw), e)
	}
	for range 2 {
		if _, e = g.Call(context.Background(), "rules", nil, time.Second); e == nil {
			t.Fatal("accepted malformed/oversized JSON")
		}
	}
	if _, e = g.Call(context.Background(), "rules", nil, time.Millisecond); e == nil {
		t.Fatal("accepted exhausted budget")
	}
	text, e := Vision(runtime)(context.Background(), []byte{1, 2, 3}, "image/png")
	if e != nil || text != "图片转录文字" {
		t.Fatal(text, e)
	}
}
func TestGatewayBoundedQueueDeadlineCancellationAndClose(t *testing.T) {
	entered := make(chan struct{}, 10)
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		entered <- struct{}{}
		<-r.Context().Done()
	}))
	defer s.Close()
	g := NewGateway(context.Background(), gatewayRuntime(t, s), NewStats())
	var wg sync.WaitGroup
	for range 6 {
		wg.Go(func() { _, _ = g.Call(context.Background(), "rules", nil, 5*time.Second) })
	}
	for range 2 {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("workers did not start")
		}
	}
	deadline := time.Now().Add(time.Second)
	for len(g.slots) < 6 {
		if time.Now().After(deadline) {
			t.Fatal("queue not populated")
		}
		time.Sleep(time.Millisecond)
	}
	start := time.Now()
	if _, e := g.Call(context.Background(), "rules", nil, time.Second); e == nil || time.Since(start) > 100*time.Millisecond {
		t.Fatal("unbounded queue", e)
	}
	g.Close()
	wg.Wait()
	if len(g.slots) != 0 || len(g.workers) != 0 {
		t.Fatal("permits leaked")
	}
	if _, e := g.Call(context.Background(), "rules", nil, time.Second); e == nil {
		t.Fatal("accepted after close")
	}
	g = NewGateway(context.Background(), gatewayRuntime(t, s), NewStats())
	start = time.Now()
	if _, e := g.Call(context.Background(), "rules", nil, 100*time.Millisecond); e == nil || time.Since(start) > 2*time.Second {
		t.Fatal("deadline ignored", e)
	}
	g.Close()
}
