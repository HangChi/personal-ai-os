package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"
)

// Exercise real orchestration with one SQL boundary failing at a time.
// The oracle does not implement rollback; PostgreSQL integration tests own that guarantee.
func TestRunsStartStorageFailuresNeverLaunchAndReleasePermits(t *testing.T) {
	for _, tc := range []struct {
		name, op, match string
		occurrence      int
	}{
		{"preflight", "query", "FROM chat_run WHERE conversation_id", 1},
		{"second read", "query", "FROM chat_run WHERE conversation_id", 2},
		{"running query", "query", "SELECT EXISTS(SELECT 1 FROM chat_run WHERE conversation_id", 1},
		{"latest read", "query", "ORDER BY created_at DESC", 1},
		{"foreign id query", "query", "SELECT EXISTS(SELECT 1 FROM chat_run WHERE id=", 1},
		{"queued request", "query", "SELECT request,status,mode,conversation_id", 1},
		{"insert execution", "exec", "INSERT INTO chat_run(", 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			failure := errors.New("injected storage failure")
			hits := 0
			state := &lifecycleState{}
			r := lifecycleFixture(t, state, func(next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
				return func(op, q string, a []driver.NamedValue) (sqlStep, error) {
					if op == tc.op && strings.Contains(q, tc.match) {
						hits++
						if hits == tc.occurrence {
							return sqlStep{}, failure
						}
					}
					return next(op, q, a)
				}
			})
			out, err := r.Start(context.Background(), "contract", "run", StreamRequest{Content: "目标"})
			if !errors.Is(err, failure) || out != nil {
				t.Fatal("failure did not propagate", out, err, hits)
			}
			if err = r.Close(context.Background()); err != nil {
				t.Fatal(err)
			}
			if len(r.active) != 0 || r.Engine.Metrics.Active.Load() != 0 {
				t.Fatal("execution launched after storage failure")
			}
			a, err := r.Gate.TryAcquire(context.Background())
			if err != nil || a == nil {
				t.Fatal("capacity leaked", err)
			}
			b, err := r.Gate.TryAcquire(context.Background())
			if err != nil || b == nil {
				t.Fatal("capacity leaked", err)
			}
			_ = a.Release(context.Background())
			_ = b.Release(context.Background())
		})
	}
}

func TestRunsResumeStorageFailuresCannotReplayWrites(t *testing.T) {
	for _, tc := range []struct {
		name, op, match string
		occurrence      int
	}{
		{"read run", "query", "FROM chat_run WHERE conversation_id", 2},
		{"running", "query", "SELECT EXISTS(SELECT 1 FROM chat_run WHERE conversation_id", 1},
		{"question", "query", "FROM agent_question WHERE run_id", 2},
		{"pending confirmation", "query", "SELECT EXISTS(SELECT 1 FROM confirmation", 1},
		{"uncertain outcome", "query", "SELECT EXISTS(SELECT 1 FROM operation_record", 2},
		{"transition", "exec", "UPDATE chat_run SET status='RUNNING'", 1},
		{"summary marker", "exec", "INSERT INTO chat_run_event", 1},
		{"steering", "query", "FROM agent_followup WHERE", 2},
		{"transcript", "query", "SELECT question,answer", 1},
		{"receipts", "query", "SELECT operation,outcome", 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			failure := errors.New("injected recovery failure")
			hits := 0
			state := &lifecycleState{status: "INTERRUPTED", request: stringMustJSON(StreamRequest{Content: "目标"})}
			r := lifecycleFixture(t, state, func(next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
				return func(op, q string, a []driver.NamedValue) (sqlStep, error) {
					if op == tc.op && strings.Contains(q, tc.match) {
						hits++
						if hits == tc.occurrence {
							return sqlStep{}, failure
						}
					}
					if op == "exec" && strings.Contains(q, "UPDATE chat_run SET status='INTERRUPTED'") {
						state.mu.Lock()
						state.status = "INTERRUPTED"
						state.mu.Unlock()
						return sqlStep{affected: 1}, nil
					}
					return next(op, q, a)
				}
			})
			out, err := r.Resume(context.Background(), "contract", "run", "")
			if !errors.Is(err, failure) || out != nil {
				t.Fatal("recovery failure did not propagate", out, err, hits)
			}
			_ = r.Close(context.Background())
			if len(r.active) != 0 || r.Engine.Metrics.Active.Load() != 0 {
				t.Fatal("recovery launched after failure")
			}
			state.mu.Lock()
			defer state.mu.Unlock()
			for _, event := range state.events {
				if event.Name == "done" {
					t.Fatal("failed recovery reported done")
				}
			}
		})
	}
}
