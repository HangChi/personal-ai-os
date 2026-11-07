package chatcontext

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

type brokenPreferences struct{}

func (brokenPreferences) Load(context.Context) (*int, error) {
	return nil, errors.New("preferences unavailable")
}
func (brokenPreferences) Save(context.Context, int) error { return nil }

func TestBudgetEvidenceSourcesAndExplicitInputReserves(t *testing.T) {
	ctx := context.Background()
	ai := config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 131072}}
	if _, e := NewBudget(ctx, config.AIProperties{}, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil); e == nil {
		t.Fatal("invalid config accepted")
	}
	if _, e := NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), brokenPreferences{}, nil, nil, nil, nil); e == nil {
		t.Fatal("load failure ignored")
	}
	configured := 65536
	b, e := NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), nil, func(context.Context, string) *int { return &configured }, nil, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	if w := b.Window(ctx, "gpt-4o"); w.Source != "configured" || w.Tokens != configured {
		t.Fatal(w)
	}
	b.lookup = nil
	if w := b.Window(ctx, "gpt-4o"); w.Source != "catalog" {
		t.Fatal(w)
	}
	epoch := b.Evidence.Generation()
	b.Evidence.Rejected(epoch, "gpt-4o", errors.New("maximum context length is 8192; max_input_tokens 4096 exceeded"), 10000)
	if w := b.Window(ctx, "gpt-4o"); w.Source != "endpoint" || w.Tokens != 8192 {
		t.Fatal(w)
	}
	if b.InputSource(ctx, "gpt-4o") != "endpoint" || b.InputLimit(ctx, "gpt-4o", 1024) != 4096-b.Margin(8192)-1024 {
		t.Fatal("explicit reserve")
	}
	b.Evidence.Rejected(epoch, "adaptive", errors.New("context_length_exceeded"), 4096)
	if b.InputSource(ctx, "adaptive") != "adaptive" || b.InputLimit(ctx, "adaptive", 1024) >= 4096 {
		t.Fatal("adaptive bound")
	}
	maxTokens := 100
	got, e := b.Prepare(ctx, model.ChatRequest{MaxTokens: &maxTokens, Messages: []model.Message{{Role: "user", Content: "hello"}}}, "fixture", epoch, 0, 0)
	if e != nil || *got.MaxTokens != 100 {
		t.Fatal(got, e)
	}
}

func TestUTF16SplitAndTokenizerEdgeCases(t *testing.T) {
	for _, tc := range []struct {
		start, end int
		want       string
	}{{0, 0, ""}, {0, 1, "?"}, {1, 2, "?"}, {0, 2, "😀"}} {
		if v := JavaSubstring("😀", tc.start, tc.end); v != tc.want {
			t.Fatal(tc, v)
		}
	}
	if encoding, known := encodingFor("text-embedding-3-small"); encoding != "cl100k_base" || !known {
		t.Fatal(encoding, known)
	}
	if pieceLength("\x00", "cl100k_base") != 1 || pieceTokens("", nil) != 0 {
		t.Fatal("token boundary")
	}
}

func TestSuccessfulImageRequestsDoNotCalibrateTextTokenRatio(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1200}}\n\ndata: [DONE]\n\n"))
	}))
	defer server.Close()
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.openai.base-url": server.URL, "spring.ai.openai.api-key": "synthetic", "spring.ai.openai.chat.model": "fixture"}})
	if e != nil {
		t.Fatal(e)
	}
	r, e := model.NewResolver(p)
	if e != nil {
		t.Fatal(e)
	}
	client, e := model.NewChatClient(r.Resolve(model.Values{}), model.OpenAI, server.Client().Transport)
	if e != nil {
		t.Fatal(e)
	}
	b, e := NewBudget(context.Background(), config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 131072}}, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	request := model.ChatRequest{Messages: []model.Message{{Role: "user", Content: "image", Images: []model.Image{{MIME: "image/png", Data: []byte("synthetic")}}}}}
	if _, e := b.GuardStream(context.Background(), client, request, b.Evidence.Generation(), nil, func(model.ChatChunk) error { return nil }); e != nil {
		t.Fatal(e)
	}
	if b.Evidence.Snapshot(b.Evidence.Generation(), "fixture").TokenRatio != 1 {
		t.Fatal("image tokens contaminated text estimate")
	}
}
