package chat

import (
	"context"
	"strings"
	"testing"
	"time"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/platform/config"
)

func TestPromptContributionOrderAndKnowledge(t *testing.T) {
	ai, _ := config.BindAIProperties(map[string]string{"app.ai.system-prompt": "base"})
	budget, _ := chatcontext.NewBudget(context.Background(), ai, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
	assembler := &Assembler{Budget: budget, Contributors: []PromptContributor{{Order: 10, Contribute: func(context.Context, string, string) (string, error) { return "plan", nil }}, {Order: -10, Contribute: func(_ context.Context, user, id string) (string, error) {
		if user != "question" || id != "c-1" {
			t.Error("prompt scope lost")
		}
		return "time", nil
	}}}}
	got, err := assembler.Assemble(context.Background(), []Citation{{Content: "evidence", ChunkIndex: 2}}, "question", "c-1", "attachment")
	if err != nil || !strings.HasPrefix(got, "base\n\ntime\n\nplan\n\nattachment\n\n以下内容") || !strings.Contains(got, "[1] 文档《未命名》（未命名，第 3 段）\nevidence") {
		t.Fatal(got, err)
	}
}

func TestSummaryTranscriptRetainsRolesAndOversizedEdges(t *testing.T) {
	if got := BuildTranscript([]Message{{Role: "USER", Content: "one"}, {Role: "TOOL", Content: "two"}}, 100); got != "用户：one\n工具：two" {
		t.Fatal(got)
	}
	got := BuildTranscript([]Message{{Role: "USER", Content: strings.Repeat("首", 100) + strings.Repeat("尾", 100)}}, 70)
	if chatcontext.JavaLength(got) != 70 || !strings.HasPrefix(got, "用户：首") || !strings.HasSuffix(got, "尾尾") || !strings.Contains(got, "仅保留首尾") {
		t.Fatal(got)
	}
	zone, _ := time.LoadLocation("Asia/Shanghai")
	now := time.Date(2026, 11, 4, 4, 5, 6, 0, time.UTC)
	text, err := CurrentTimeContributor(zone, func() time.Time { return now }).Contribute(context.Background(), "", "")
	if err != nil || !strings.Contains(text, "2026-11-04 星期三") || !strings.Contains(text, "明天日期：2026-11-05") {
		t.Fatal(text, err)
	}
}
