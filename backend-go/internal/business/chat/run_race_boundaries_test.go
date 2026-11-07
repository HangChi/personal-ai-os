package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"
)

func TestRunsStartRechecksIdentityAndStateAfterAcquiringCapacity(t *testing.T) {
	for _, mode := range []string{"same", "changed", "running", "question", "question-error", "foreign", "queue-changed", "queue-write", "closed"} {
		t.Run(mode, func(t *testing.T) {
			state := &lifecycleState{}
			reads := 0
			var r *Runs
			closed := false
			r = lifecycleFixture(t, state, func(next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
				return func(op, q string, args []driver.NamedValue) (sqlStep, error) {
					if op == "query" && strings.Contains(q, "SELECT id,conversation_id,status,request FROM chat_run") {
						reads++
						if reads == 2 && (mode == "same" || mode == "changed") {
							state.mu.Lock()
							state.status = "COMPLETED"
							state.request = stringMustJSON(StreamRequest{Content: "目标"})
							if mode == "changed" {
								state.request = stringMustJSON(StreamRequest{Content: "不同目标"})
							}
							row := sqlValues("run", "contract", state.status, state.request)
							state.mu.Unlock()
							return sqlStep{rows: [][]driver.Value{row}}, nil
						}
						if strings.Contains(q, "ORDER BY created_at DESC") && (mode == "question" || mode == "question-error") {
							return sqlStep{rows: [][]driver.Value{runRow("WAITING_USER")}}, nil
						}
					}
					if op == "query" && strings.Contains(q, "SELECT EXISTS(SELECT 1 FROM chat_run") {
						if mode == "running" && strings.Contains(q, "status='RUNNING'") || mode == "foreign" && strings.Contains(q, "WHERE id=$1") {
							return sqlStep{rows: [][]driver.Value{sqlValues(true)}}, nil
						}
					}
					if op == "query" && strings.Contains(q, "FROM agent_question WHERE") && (mode == "question" || mode == "question-error") {
						if mode == "question-error" {
							return sqlStep{}, errors.New("question read failed")
						}
						return sqlStep{rows: [][]driver.Value{questionRow("PENDING", nil)}}, nil
					}
					if op == "query" && strings.Contains(q, "SELECT request,status,mode,conversation_id") && (mode == "queue-changed" || mode == "queue-write") {
						content := "目标"
						if mode == "queue-changed" {
							content = "不同目标"
						}
						return sqlStep{rows: [][]driver.Value{sqlValues(stringMustJSON(StreamRequest{Content: content}), "PENDING", "QUEUE", "contract")}}, nil
					}
					if op == "exec" && strings.Contains(q, "UPDATE agent_followup SET status='APPLIED'") && mode == "queue-write" {
						return sqlStep{}, errors.New("queue update failed")
					}
					if op == "exec" && strings.Contains(q, "UPDATE chat_run SET status='INTERRUPTED'") {
						state.mu.Lock()
						state.status = "INTERRUPTED"
						state.mu.Unlock()
						return sqlStep{affected: 1}, nil
					}
					step, e := next(op, q, args)
					if mode == "closed" && op == "exec" && strings.Contains(q, "INSERT INTO chat_run(") {
						closed = true
					}
					if mode == "closed" && op == "commit" && closed {
						_ = r.Close(context.Background())
					}
					return step, e
				}
			})
			got, e := r.Start(context.Background(), "contract", "run", StreamRequest{Content: "目标"})
			if mode == "same" {
				if e != nil || got == nil || got.Status != "COMPLETED" {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("conflicting start accepted", mode, got)
			}
			if len(r.active) != 0 {
				t.Fatal("conflicting start launched")
			}
		})
	}
}

func TestRunMutationRejectsExecutionsRemovedDuringTheRequest(t *testing.T) {
	for _, op := range []string{"answer", "followup", "change"} {
		t.Run(op, func(t *testing.T) {
			steps := append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id"), terminalStep("rollback"))
			r := runsFixture(t, steps...)
			var e error
			switch op {
			case "answer":
				_, e = r.Answer(context.Background(), "contract", "run", "question", "answer")
			case "followup":
				_, e = r.Followup(context.Background(), "contract", "run", "input", "QUEUE", StreamRequest{Content: "followup"})
			case "change":
				_, e = r.ChangeFollowup(context.Background(), "contract", "run", "input", "cancel", "")
			}
			if e == nil {
				t.Fatal("missing execution accepted")
			}
		})
	}
}
