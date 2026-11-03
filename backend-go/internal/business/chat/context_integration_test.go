//go:build integration

package chat

import (
	"context"
	"database/sql"
	"strings"
	"testing"
	"time"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

func TestPostgresP4ContextHistorySummaryAndStaleRewindFence(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, store := fixtureService(t, pool, nil)
	session := fixtureSession(t, svc, "context")
	ai, _ := config.BindAIProperties(map[string]string{"app.ai.memory.max-messages": "3"})
	budget, err := chatcontext.NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), nil, nil, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = budget.Set(ctx, 1)
	for i := 0; i < 12; i++ {
		role := "USER"
		if i%2 != 0 {
			role = "TOOL"
		}
		if err = svc.Append(ctx, session.ConversationID, []NewMessage{{Role: role, Content: strings.Repeat("字", 1000)}}); err != nil {
			t.Fatal(err)
		}
	}
	history := &History{Store: store, Budget: budget}
	window, err := history.Window(ctx, session.ConversationID)
	if err != nil || len(window.Messages) != 1 || window.StartSequence != 12 {
		t.Fatal(window, err)
	}
	messages, err := history.ModelMessages(ctx, session.ConversationID)
	if err != nil || messages[0].Role != "assistant" {
		t.Fatal(messages, err)
	}
	all, err := svc.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(all) != 12 {
		t.Fatal("window overwrote history", len(all), err)
	}
	summary := &Summary{Store: store, History: history, Properties: config.ContextSummary{Enabled: true, MinMessages: 4, MaxExcerptChars: 4000, MaxSummaryChars: 80, Timeout: time.Second}, Model: func(callCtx context.Context, request model.ChatRequest) (model.ChatResult, error) {
		if len(request.Messages) != 2 || request.Messages[0].Content != summarySystem {
			t.Error("wrong plain summary prompt")
		}
		err := pool.WithConn(callCtx, func(c *sql.Conn) error { var n int; return c.QueryRowContext(callCtx, `SELECT 1`).Scan(&n) })
		return model.ChatResult{Content: strings.Repeat("总结", 100)}, err
	}}
	stale, err := summary.Snapshot(ctx, session.ConversationID, nil)
	if err != nil || stale == nil || stale.Boundary != 3 {
		t.Fatal(stale, err)
	}
	if _, err = svc.Rewind(ctx, session.ConversationID, 3); err != nil {
		t.Fatal(err)
	}
	if err = svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: "replacement"}}); err != nil {
		t.Fatal(err)
	}
	if applied, err := summary.Apply(ctx, stale, "stale summary"); err != nil || applied {
		t.Fatal("deleted historical summary resurrected", applied, err)
	}
	boundary := 2
	if applied, err := summary.Refresh(ctx, session.ConversationID, &boundary); err != nil || !applied {
		t.Fatal(applied, err)
	}
	current, err := svc.Require(ctx, session.ConversationID)
	if err != nil || current.SummaryUptoSeq != 2 || len([]rune(*current.Summary)) != 80 {
		t.Fatal(current, err)
	}
	if applied, err := summary.Apply(ctx, stale, "overwriting summary"); err != nil || applied {
		t.Fatal("optimistic summary update lost", applied, err)
	}
	if rendered, err := summary.Render(ctx, session.ConversationID); err != nil || !strings.HasPrefix(rendered, "## 早前对话摘要\n\n") {
		t.Fatal(rendered, err)
	}
}

func TestPostgresP4ContextPreferencePersistenceAndSummaryTimeout(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, store := fixtureService(t, pool, nil)
	session := fixtureSession(t, svc, "summary timeout")
	execute(t, pool, `DELETE FROM chat_context_preference`)
	t.Cleanup(func() { execute(t, pool, `DELETE FROM chat_context_preference`) })
	ai, _ := config.BindAIProperties(nil)
	preferences := chatcontext.PostgresPreferences{Pool: pool}
	budget, err := chatcontext.NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), preferences, nil, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = budget.Set(ctx, 524288); err != nil {
		t.Fatal(err)
	}
	reloaded, err := chatcontext.NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), preferences, nil, nil, nil, nil)
	if err != nil || reloaded.Selected() != 524288 {
		t.Fatal(err)
	}
	if err = svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: "history"}, {Role: "ASSISTANT", Content: "reply"}}); err != nil {
		t.Fatal(err)
	}
	summary := &Summary{Store: store, History: &History{Store: store, Budget: budget}, Properties: config.ContextSummary{Enabled: true, MinMessages: 1, MaxExcerptChars: 4000, MaxSummaryChars: 80, Timeout: 20 * time.Millisecond}, Model: func(ctx context.Context, _ model.ChatRequest) (model.ChatResult, error) {
		<-ctx.Done()
		return model.ChatResult{}, ctx.Err()
	}}
	boundary := 2
	if updated, err := summary.Refresh(ctx, session.ConversationID, &boundary); err == nil || updated {
		t.Fatal("summary timeout ignored")
	}
	if current, err := svc.Require(ctx, session.ConversationID); err != nil || current.Summary != nil || current.SummaryUptoSeq != 0 {
		t.Fatal("failed summary changed history", err)
	}
}
