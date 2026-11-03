package agent

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

type memoryAudit struct {
	mu      sync.Mutex
	records map[string]idempotency.Record
}

func (m *memoryAudit) Find(_ context.Context, k string) (idempotency.Record, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.records[k]
	return r, ok, nil
}
func (m *memoryAudit) Claim(_ context.Context, r idempotency.Record) (idempotency.Record, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if old, ok := m.records[r.Key]; ok {
		return old, false, nil
	}
	m.records[r.Key] = r
	return r, true, nil
}
func (*memoryAudit) DeleteStaleHTTP(context.Context, idempotency.Record, time.Time) (bool, error) {
	return false, nil
}
func (m *memoryAudit) Complete(_ context.Context, k string, o idempotency.Outcome, p *string, d int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	r := m.records[k]
	r.Outcome = o
	r.ResponsePayload = p
	r.DurationMS = &d
	m.records[k] = r
	return nil
}
func testTurn() *Turn {
	return &Turn{ConversationID: "c", ID: "t", Budget: NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: 12, MaxConsecutiveFailures: 3})}
}
func toolCall(args string) model.ToolCall {
	var v model.ToolCall
	v.ID = "call"
	v.Function.Name = "write"
	v.Function.Arguments = args
	return v
}
func TestDurableToolReplayAndFailureBudget(t *testing.T) {
	m := &memoryAudit{records: map[string]idempotency.Record{}}
	idem, _ := idempotency.NewService(m)
	var count atomic.Int32
	registry, e := NewRegistry(idem, nil, []Definition{{Keys: []string{"value"}, Model: model.Tool{Name: "write", Parameters: ObjectSchema(map[string]any{"value": StringSchema()})}, Write: true, Action: func(_ context.Context, _ *Turn, args map[string]any) (Result, error) {
		count.Add(1)
		if Text(args, "value") == "bad" {
			return Result{}, errors.New("secret internal failure")
		}
		return OK("created", map[string]string{"id": "one"}), nil
	}}})
	if e != nil {
		t.Fatal(e)
	}
	first, e := registry.Execute(context.Background(), testTurn(), toolCall(`{"value":"same"}`))
	if e != nil || first.Status != "OK" {
		t.Fatal(first, e)
	}
	turn := testTurn()
	replay, e := registry.Execute(context.Background(), turn, toolCall(`{"value":"same"}`))
	if e != nil || replay.Status != "DUPLICATE" || count.Load() != 1 || !turn.Budget.HasWrite("write") {
		t.Fatal(replay, count.Load(), e)
	}
	failed := testTurn()
	result, e := registry.Execute(context.Background(), failed, toolCall(`{"value":"bad"}`))
	if e != nil || result.Status != "ERROR" {
		t.Fatal(result, e)
	}
	if _, e = registry.Execute(context.Background(), failed, toolCall(`{"value":"bad"}`)); e == nil {
		t.Fatal("identical failed call repeated")
	}
	result, e = registry.Execute(context.Background(), testTurn(), toolCall(`{"value":"bad"}`))
	if e != nil || result.Status != "REJECTED" || count.Load() != 2 {
		t.Fatal("failed receipt was replayed as success or reexecuted", result, e, count.Load())
	}
}
func TestInvalidToolsAreAuditedAndCannotEvadeBudget(t *testing.T) {
	m := &memoryAudit{records: map[string]idempotency.Record{}}
	idem, _ := idempotency.NewService(m)
	registry, _ := NewRegistry(idem, nil, nil)
	turn := testTurn()
	for i, raw := range []string{`{`, `[]`, `null`} {
		result, e := registry.Execute(context.Background(), turn, toolCall(raw))
		if e != nil || result.Status != "REJECTED" {
			t.Fatal(i, result, e)
		}
	}
	if _, e := registry.Execute(context.Background(), turn, toolCall(`{}`)); e == nil {
		t.Fatal("failed binding did not consume budget")
	}
	if len(m.records) != 3 {
		t.Fatal("missing rejected audit", len(m.records))
	}
}
func TestOrderedDigestAndModelFailureBudgets(t *testing.T) {
	got, e := ArgumentsDigest(map[string]any{"z": "<中文>", "a": 1}, []string{"z", "a", "optional"})
	if e != nil || got != idempotency.SHA256(`{"z":"<中文>","a":1,"optional":null}`) {
		t.Fatal(got, e)
	}
	b := NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: 2, MaxConsecutiveFailures: 2})
	if b.BeforeModel() != nil || b.BeforeModel() != nil || b.BeforeModel() == nil {
		t.Fatal("model budget")
	}
	b = NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: 10, MaxConsecutiveFailures: 2})
	b.Record("read", "a", Result{Status: "ERROR"}, false)
	b.Record("read", "b", OK("ok", nil), false)
	b.Record("read", "c", Result{Status: "REJECTED"}, false)
	if b.BeforeModel() != nil {
		t.Fatal("success did not reset consecutive failures")
	}
	b.Record("read", "d", Result{Status: "ERROR"}, false)
	if b.BeforeModel() == nil {
		t.Fatal("consecutive failures ignored")
	}
	if Strip("\u00a0x\u00a0") != "\u00a0x\u00a0" || Strip("\u3000x\u3000") != "x" {
		t.Fatal("Java whitespace changed")
	}
}
