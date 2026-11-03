package chatcontext

import (
	"context"
	"errors"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

type preferences struct {
	value int
	fail  bool
}

func (p *preferences) Load(context.Context) (*int, error) {
	if p.value == 0 {
		return nil, nil
	}
	v := p.value
	return &v, nil
}
func (p *preferences) Save(_ context.Context, n int) error {
	if p.fail {
		return errors.New("synthetic failure")
	}
	p.value = n
	return nil
}

func TestBudgetPreferenceClampDoesNotOverwriteSelection(t *testing.T) {
	ctx := context.Background()
	pref := &preferences{}
	b, err := NewBudget(ctx, config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 131072}}, config.DefaultContextProtectionProperties(), pref, nil, nil, nil, func() int { return 4000 })
	if err != nil {
		t.Fatal(err)
	}
	if err = b.Set(ctx, 300000); err != nil {
		t.Fatal(err)
	}
	if b.ForWindow(8192) != 5632 || b.Selected() != 300000 {
		t.Fatal(b.ForWindow(8192), b.Selected())
	}
	pref.fail = true
	if b.Set(ctx, 1000) == nil || b.Selected() != 300000 {
		t.Fatal("failed write changed live budget")
	}
	pref.fail = false
	if err = b.Set(ctx, 1); err != nil {
		t.Fatal(err)
	}
	if b.ForWindow(131072) != 5024 || b.CharBudget(ctx, "") != 1638 {
		t.Fatal(b.ForWindow(131072), b.CharBudget(ctx, ""))
	}
}

func TestContextGuardPreservesNewestUserAndToolPairs(t *testing.T) {
	b, _ := NewBudget(context.Background(), config.AIProperties{Memory: config.AIMemory{MaxMessages: 20, ContextWindowTokens: 8192}}, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
	_ = b.Set(context.Background(), 1600)
	request := model.ChatRequest{Messages: []model.Message{{Role: "system", Content: "system"}, {Role: "user", Content: repeated("旧历史", 2000)}, {Role: "assistant", Content: "old response"}, {Role: "user", Content: "current"}, {Role: "assistant", ToolCalls: []model.ToolCall{{ID: "call1", Function: model.ToolFunction{Name: "tool", Arguments: "{}"}}}}, {Role: "tool", ToolCallID: "call1", Name: "tool", Content: repeated("查询结果", 2000)}}}
	got, err := b.Prepare(context.Background(), request, "qwen-fixture", b.Evidence.Generation(), 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Messages) != 4 || got.Messages[0].Role != "system" || got.Messages[1].Content != "current" || got.Messages[2].ToolCalls[0].ID != got.Messages[3].ToolCallID {
		t.Fatal("broken message/tool pairing", got.Messages)
	}
	if request.Messages[5].Content == got.Messages[3].Content {
		t.Fatal("oversized tool result not shortened")
	}
	_, err = b.Prepare(context.Background(), model.ChatRequest{Messages: []model.Message{{Role: "system", Content: repeated("不可压缩系统提示", 5000)}, {Role: "user", Content: "current"}}}, "qwen-fixture", 0, 0, 0)
	if err == nil {
		t.Fatal("irreducible prompt accepted")
	}
}

func repeated(s string, n int) string {
	var out string
	for i := 0; i < n; i++ {
		out += s
	}
	return out
}
