package agent

import (
	"context"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

var commandConfig = config.AgentExecutionProperties{MaxModelCalls: 12, MaxConsecutiveFailures: 2}

const commandKey = "10000000-0000-4000-8000-000000000001"

func commandRegistry(t *testing.T, write bool, action func(context.Context, *Turn, map[string]any) (Result, error)) *Registry {
	t.Helper()
	idem, _ := idempotency.NewService(&memoryAudit{records: map[string]idempotency.Record{}})
	r, e := NewRegistry(idem, nil, []Definition{{Model: model.Tool{Name: "mcp_test_change", Description: "test", Parameters: ObjectSchema(map[string]any{"nested": map[string]any{"type": "object"}})}, Write: write, Action: action}})
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestDirectCommandsBindIdentityAndReplayNestedArguments(t *testing.T) {
	var count atomic.Int32
	r := commandRegistry(t, true, func(_ context.Context, turn *Turn, _ map[string]any) (Result, error) {
		if turn.ID != "cmd:"+commandKey || turn.ConversationID != "c" || turn.Budget == nil {
			t.Error(turn)
		}
		count.Add(1)
		return OK("done", map[string]int{"remaining": 2}), nil
	})
	request := DirectToolRequest{Tool: "mcp_test_change", Arguments: map[string]any{"nested": map[string]any{"z": 2, "a": 1}}}
	first, e := r.ExecuteCommand(context.Background(), "c", commandKey, request, commandConfig)
	if e != nil || first.Replayed || first.Result.Status != "OK" {
		t.Fatal(first, e)
	}
	request.Arguments = map[string]any{"nested": map[string]any{"a": 1, "z": 2}}
	replay, e := r.ExecuteCommand(context.Background(), "c", strings.ToUpper(commandKey), request, commandConfig)
	if e != nil || !replay.Replayed || count.Load() != 1 {
		t.Fatal(replay, e, count.Load())
	}
	for _, changed := range []struct {
		conv string
		args map[string]any
	}{{"other", request.Arguments}, {"c", map[string]any{"nested": map[string]any{"z": 3}}}} {
		_, e = r.ExecuteCommand(context.Background(), changed.conv, commandKey, DirectToolRequest{Tool: request.Tool, Arguments: changed.args}, commandConfig)
		var be *api.BusinessError
		if !errors.As(e, &be) || be.Code() != api.IdempotencyKeyConflict {
			t.Fatalf("identity conflict: %v", e)
		}
	}
	if count.Load() != 1 {
		t.Fatal(count.Load())
	}
}
func TestDirectCommandsConcurrentRetryExecutesOnce(t *testing.T) {
	var calls atomic.Int32
	r := commandRegistry(t, true, func(context.Context, *Turn, map[string]any) (Result, error) {
		calls.Add(1)
		return OK("done", nil), nil
	})
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Go(func() {
			_, _ = r.ExecuteCommand(context.Background(), "c", commandKey, DirectToolRequest{Tool: "mcp_test_change", Arguments: map[string]any{}}, commandConfig)
		})
	}
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatal(calls.Load())
	}
}
func TestDirectReadRetryAndConfirmationKeepOriginalReceipt(t *testing.T) {
	for _, status := range []string{"OK", "REQUIRES_CONFIRMATION"} {
		t.Run(status, func(t *testing.T) {
			var calls int
			id := "confirmation-one"
			r := commandRegistry(t, false, func(context.Context, *Turn, map[string]any) (Result, error) {
				calls++
				return Result{Status: status, Message: "done", ConfirmationID: &id}, nil
			})
			request := DirectToolRequest{Tool: "mcp_test_change", Arguments: map[string]any{}}
			for i := 0; i < 2; i++ {
				result, e := r.ExecuteCommand(context.Background(), "c", commandKey, request, commandConfig)
				if e != nil || result.Result.ConfirmationID == nil || *result.Result.ConfirmationID != id || result.Replayed != (i == 1) {
					t.Fatal(result, e)
				}
			}
			if calls != 1 {
				t.Fatal(calls)
			}
			_, e := r.ExecuteCommand(context.Background(), "c", UUID(), request, commandConfig)
			if e != nil || calls != 2 {
				t.Fatal(calls, e)
			}
		})
	}
}
func TestDirectUnknownFailureDoesNotReexecute(t *testing.T) {
	var quantity = 3
	r := commandRegistry(t, true, func(context.Context, *Turn, map[string]any) (Result, error) {
		quantity--
		return Result{Status: "ERROR", Message: "unknown remote outcome"}, nil
	})
	request := DirectToolRequest{Tool: "mcp_test_change", Arguments: map[string]any{}}
	first, e := r.ExecuteCommand(context.Background(), "c", commandKey, request, commandConfig)
	if e != nil || first.Result.Status != "ERROR" {
		t.Fatal(first, e)
	}
	_, e = r.ExecuteCommand(context.Background(), "c", commandKey, request, commandConfig)
	if e == nil || !strings.Contains(e.Error(), "核对") || quantity != 2 {
		t.Fatal(quantity, e)
	}
}
func TestDirectCommandsValidateBeforeExecutionAndFilterCatalog(t *testing.T) {
	var calls int
	r := commandRegistry(t, true, func(context.Context, *Turn, map[string]any) (Result, error) { calls++; return OK("done", nil), nil })
	r.definitions["askUser"] = Definition{Model: model.Tool{Name: "askUser"}}
	r.definitions["startPlanStep"] = Definition{Model: model.Tool{Name: "startPlanStep"}}
	for _, test := range []struct {
		key, tool string
		args      map[string]any
	}{{"bad", "mcp_test_change", map[string]any{}}, {commandKey, "askUser", map[string]any{}}, {commandKey, "missing", map[string]any{}}, {commandKey, "mcp_test_change", nil}, {commandKey, "mcp_test_change", map[string]any{"big": strings.Repeat("a", 65537)}}} {
		_, e := r.ExecuteCommand(context.Background(), "c", test.key, DirectToolRequest{Tool: test.tool, Arguments: test.args}, commandConfig)
		if e == nil {
			t.Fatal(test)
		}
	}
	if calls != 0 {
		t.Fatal(calls)
	}
	tools := r.DirectTools()
	if len(tools) != 1 || tools[0].Name != "mcp_test_change" || !strings.Contains(tools[0].InputSchema, "object") {
		t.Fatal(tools)
	}
}
