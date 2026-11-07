package chat

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/coordination"
)

func runRow(status string) []driver.Value {
	return sqlValues("run", "contract", status, `{"content":"目标"}`)
}
func runReadSteps(status string, uncertain bool) []sqlStep {
	return []sqlStep{terminalStep("begin"), queryStep("FROM chat_run WHERE conversation_id", runRow(status)), queryStep("FROM chat_run_event WHERE run_id", sqlValues(1, "delta", `{"content":"已保存"}`)), queryStep("FROM agent_question WHERE run_id"), queryStep("FROM agent_followup WHERE"), queryStep("FROM operation_record WHERE turn_id", sqlValues(uncertain)), terminalStep("commit")}
}
func runMutateSteps() []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock_shared"), queryStep("SELECT conversation_id,read_only_report", sqlValues("contract", false))}
}
func runsFixture(t *testing.T, steps ...sqlStep) *Runs {
	t.Helper()
	e, _, _ := engineFixture(t)
	pool := contractPool(t, steps...)
	r := NewRuns(context.Background(), pool, e, &Interactions{Pool: pool}, nil, nil, nil, false)
	t.Cleanup(func() {
		if err := r.Close(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return r
}
func TestRunsGetReplaysOrderedEventsAndReportsUncertainWrites(t *testing.T) {
	for _, uncertain := range []bool{false, true} {
		t.Run(map[bool]string{false: "continue", true: "summary-only"}[uncertain], func(t *testing.T) {
			steps := runReadSteps("INTERRUPTED", uncertain)
			steps[2].check = func(a []driver.NamedValue) {
				if a[1].Value != int64(0) {
					t.Fatal("negative cursor not normalized", a)
				}
			}
			r := runsFixture(t, steps...)
			got, e := r.Get(context.Background(), "contract", "run", -7)
			mode := "CONTINUE"
			if uncertain {
				mode = "SUMMARY_ONLY"
			}
			if e != nil || got.RecoveryMode != mode || len(got.Events) != 1 || got.Events[0].ID != 1 || got.request.Content != "目标" || got.Followups == nil {
				t.Fatal(got, e)
			}
		})
	}
	for _, latest := range []bool{false, true} {
		t.Run(map[bool]string{false: "missing-id", true: "no-latest"}[latest], func(t *testing.T) {
			end := "rollback"
			id := "run"
			if latest {
				end = "commit"
				id = ""
			}
			r := runsFixture(t, terminalStep("begin"), queryStep("FROM chat_run WHERE conversation_id"), terminalStep(end))
			got, e := r.Get(context.Background(), "contract", id, 0)
			if latest {
				if e != nil || got != nil {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("missing execution accepted")
			}
		})
	}
	for _, phase := range []int{1, 2, 3, 4, 5} {
		t.Run(string(rune('0'+phase)), func(t *testing.T) {
			steps := runReadSteps("INTERRUPTED", false)
			failure := errors.New("storage read failed")
			steps = steps[:phase+1]
			steps[phase].err = failure
			steps = append(steps, terminalStep("rollback"))
			r := runsFixture(t, steps...)
			if _, e := r.Get(context.Background(), "contract", "run", 0); !errors.Is(e, failure) {
				t.Fatal(e)
			}
		})
	}
}
func TestRunsStartIdempotentReplayAndConflictingPayload(t *testing.T) {
	for _, same := range []bool{true, false} {
		t.Run(map[bool]string{true: "replay", false: "conflict"}[same], func(t *testing.T) {
			steps := append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id", runRow("COMPLETED")))
			if same {
				steps = append(steps, terminalStep("commit"))
				steps = append(steps, runReadSteps("COMPLETED", false)...)
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			r := runsFixture(t, steps...)
			content := "目标"
			if !same {
				content = "另一目标"
			}
			got, e := r.Start(context.Background(), "contract", "run", StreamRequest{Content: content})
			if same {
				if e != nil || got.Status != "COMPLETED" || len(r.active) != 0 {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("idempotency key reused")
			}
		})
	}
	r := runsFixture(t)
	for _, key := range []string{strings.Repeat("x", 65), "bad" + string(rune(0))} {
		if _, e := r.Start(context.Background(), "contract", key, StreamRequest{Content: "目标"}); e == nil {
			t.Fatal("invalid key reached SQL")
		}
	}
	if _, e := r.Start(context.Background(), "contract", "key", StreamRequest{}); e == nil {
		t.Fatal("blank request accepted")
	}
}
func TestRunsMutationGuardsReadOnlyAndMissingSession(t *testing.T) {
	for _, mode := range []string{"read-only", "missing", "lock-error"} {
		t.Run(mode, func(t *testing.T) {
			steps := runMutateSteps()
			if mode == "read-only" {
				steps[2].rows[0][1] = true
			}
			if mode == "missing" {
				steps[2].rows = nil
			}
			if mode == "lock-error" {
				steps = steps[:2]
				steps[1].err = errors.New("lock failed")
			}
			steps = append(steps, terminalStep("rollback"))
			r := runsFixture(t, steps...)
			if _, e := r.Start(context.Background(), "contract", "run", StreamRequest{Content: "目标"}); e == nil {
				t.Fatal("mutation guard bypassed")
			}
		})
	}
}
func TestRunsStopPersistsBeforeCancellationAndNotifiesSubscribers(t *testing.T) {
	for _, running := range []bool{true, false} {
		t.Run(map[bool]string{true: "running", false: "already-stopped"}[running], func(t *testing.T) {
			status := "STOPPED"
			if running {
				status = "RUNNING"
			}
			steps := runReadSteps(status, false)
			if running {
				steps = append(steps, runMutateSteps()...)
				steps = append(steps, execStep("SET status='STOPPED'"), terminalStep("commit"))
			}
			r := runsFixture(t, steps...)
			cancelled := false
			r.active["run"] = &activeRun{cancel: func() { cancelled = true }}
			ch, unwatch := r.Watch("run")
			defer unwatch()
			if e := r.Stop(context.Background(), "contract", "run"); e != nil {
				t.Fatal(e)
			}
			if cancelled != running {
				t.Fatal(cancelled)
			}
			if running {
				select {
				case <-ch:
				default:
					t.Fatal("stop not published")
				}
			}
			delete(r.active, "run")
		})
	}
}
func TestRunsFinishConfirmationsWaitsForAllPendingOperations(t *testing.T) {
	for _, mode := range []string{"finished", "pending", "wrong-state", "already-completed", "missing"} {
		t.Run(mode, func(t *testing.T) {
			status := "WAITING_CONFIRMATION"
			if mode == "wrong-state" {
				status = "INTERRUPTED"
			}
			if mode == "already-completed" {
				status = "COMPLETED"
			}
			read := queryStep("FROM chat_run WHERE conversation_id", runRow(status))
			if mode == "missing" {
				read.rows = nil
			}
			steps := append(runMutateSteps(), read)
			if mode == "finished" || mode == "pending" {
				steps = append(steps, queryStep("FROM confirmation WHERE conversation_id", sqlValues(mode == "pending")))
			}
			if mode == "finished" {
				steps = append(steps, execStep("SET status='COMPLETED'"))
			}
			valid := mode == "finished" || mode == "already-completed"
			if valid {
				steps = append(steps, terminalStep("commit"))
				steps = append(steps, runReadSteps("COMPLETED", false)...)
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			r := runsFixture(t, steps...)
			got, e := r.FinishConfirmations(context.Background(), "contract", "run")
			if valid {
				if e != nil || got.Status != "COMPLETED" {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("confirmation boundary bypassed")
			}
		})
	}
}
func questionRow(status string, answer any) []driver.Value {
	return sqlValues("question", "run", "选择哪个版本", "[]", status, answer)
}
func TestRunsAnswerDoesNotOverwriteDecisionsOrBypassPendingWrites(t *testing.T) {
	for _, mode := range []string{"save-interrupted", "same-answer", "different-answer", "running", "pending-confirmation", "uncertain", "missing-question"} {
		t.Run(mode, func(t *testing.T) {
			status := "INTERRUPTED"
			if mode == "running" {
				status = "RUNNING"
			}
			if mode == "pending-confirmation" || mode == "uncertain" {
				status = "WAITING_USER"
			}
			q := queryStep("FROM agent_question WHERE run_id", questionRow("PENDING", nil))
			if strings.Contains(mode, "answer") {
				q.rows = [][]driver.Value{questionRow("ANSWERED", "甲")}
			}
			if mode == "missing-question" {
				q.rows = nil
			}
			steps := append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id", runRow(status)), q)
			valid := mode == "save-interrupted" || mode == "same-answer" || mode == "pending-confirmation" || mode == "uncertain"
			if mode == "save-interrupted" || mode == "pending-confirmation" || mode == "uncertain" {
				steps = append(steps, execStep("UPDATE agent_question SET answer"))
			}
			after := status
			if mode == "pending-confirmation" || mode == "uncertain" {
				steps = append(steps, queryStep("FROM confirmation WHERE conversation_id", sqlValues(mode == "pending-confirmation")), queryStep("FROM operation_record WHERE turn_id", sqlValues(mode == "uncertain")))
				after = "INTERRUPTED"
				if mode == "pending-confirmation" {
					after = "WAITING_CONFIRMATION"
				}
				write := execStep("UPDATE chat_run SET status=$2")
				write.check = func(a []driver.NamedValue) {
					if a[1].Value != after {
						t.Fatal(a)
					}
				}
				steps = append(steps, write)
			}
			if valid {
				steps = append(steps, terminalStep("commit"))
				steps = append(steps, runReadSteps(after, mode == "uncertain")...)
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			r := runsFixture(t, steps...)
			answer := " 甲 "
			if mode == "different-answer" {
				answer = "乙"
			}
			got, e := r.Answer(context.Background(), "contract", "run", "question", answer)
			if valid {
				if e != nil || got.Status != after {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("unsafe answer accepted")
			}
		})
	}
	r := runsFixture(t)
	if _, e := r.Answer(context.Background(), "contract", "run", "question", " "); e == nil {
		t.Fatal("blank answer")
	}
}
func followupRow(mode, status, content string) []driver.Value {
	b, _ := json.Marshal(StreamRequest{Content: content})
	return sqlValues("input", "run", "contract", mode, string(b), status)
}
func TestRunsQueueAndEditFollowupPreserveOriginalIntent(t *testing.T) {
	for _, mode := range []string{"create", "replay", "changed", "foreign", "full"} {
		t.Run(mode, func(t *testing.T) {
			read := queryStep("FROM agent_followup WHERE")
			if mode == "replay" || mode == "changed" {
				read.rows = [][]driver.Value{followupRow("QUEUE", "PENDING", "补充")}
			}
			if mode == "full" {
				for range 20 {
					row := followupRow("QUEUE", "PENDING", "x")
					row[0] = "another"
					read.rows = append(read.rows, row)
				}
			}
			steps := append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id", runRow("RUNNING")), read, queryStep("SELECT EXISTS(SELECT 1 FROM agent_followup", sqlValues(mode == "foreign")))
			if mode == "create" {
				write := execStep("INSERT INTO agent_followup")
				write.check = func(a []driver.NamedValue) {
					if a[0].Value != "input" || a[3].Value != "QUEUE" || !strings.Contains(a[4].Value.(string), "补充") {
						t.Fatal(a)
					}
				}
				steps = append(steps, write)
			}
			valid := mode == "create" || mode == "replay"
			if valid {
				steps = append(steps, terminalStep("commit"))
				steps = append(steps, runReadSteps("RUNNING", false)...)
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			r := runsFixture(t, steps...)
			content := "补充"
			if mode == "changed" {
				content = "变更"
			}
			_, e := r.Followup(context.Background(), "contract", "run", "input", "QUEUE", StreamRequest{Content: content})
			if (e == nil) != valid {
				t.Fatal(e)
			}
		})
	}
	for _, action := range []string{"edit", "cancel", "invalid", "edit-blank", "applied", "missing"} {
		t.Run(action, func(t *testing.T) {
			row := followupRow("QUEUE", "PENDING", "原要求")
			if action == "applied" {
				row[5] = "APPLIED"
			}
			read := queryStep("FROM agent_followup WHERE", row)
			if action == "missing" {
				read.rows = nil
			}
			steps := append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id", runRow("RUNNING")), read)
			valid := action == "edit" || action == "cancel"
			if valid {
				write := execStep("UPDATE agent_followup SET status='CANCELLED'")
				if action == "edit" {
					write = execStep("UPDATE agent_followup SET request=$2")
					write.check = func(a []driver.NamedValue) {
						var r StreamRequest
						if e := json.Unmarshal([]byte(a[1].Value.(string)), &r); e != nil || r.Content != "新要求" {
							t.Fatal(r, e)
						}
					}
				}
				steps = append(steps, write, terminalStep("commit"))
				steps = append(steps, runReadSteps("RUNNING", false)...)
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			r := runsFixture(t, steps...)
			verb := action
			content := " 新要求 "
			if action == "edit-blank" {
				verb = "edit"
				content = " "
			}
			if action == "applied" || action == "missing" {
				verb = "edit"
			}
			_, e := r.ChangeFollowup(context.Background(), "contract", "run", "input", verb, content)
			if (e == nil) != valid {
				t.Fatal(e)
			}
		})
	}
}
func TestRunsResumeTerminalGuardsAndRecoverEmptyOrBusy(t *testing.T) {
	for _, status := range []string{"RUNNING", "COMPLETED", "INTERRUPTED"} {
		t.Run(status, func(t *testing.T) {
			r := runsFixture(t, runReadSteps(status, false)...)
			got, e := r.Resume(context.Background(), "contract", "run", "")
			if status == "RUNNING" {
				if e != nil || got.Status != status {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("terminal/disabled resume accepted")
			}
		})
	}
	steps := []sqlStep{queryStep("WHERE origin='CHAT'"), queryStep("SELECT r.id,r.conversation_id,r.status")}
	r := runsFixture(t, steps...)
	r.Enabled = true
	if e := r.Recover(context.Background()); e != nil {
		t.Fatal(e)
	}
	r.Enabled = false
	if e := r.Recover(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e := r.Dispatch(context.Background()); e != nil {
		t.Fatal(e)
	}
	steps = []sqlStep{queryStep("WHERE origin='CHAT'", sqlValues("run", "contract")), terminalStep("begin"), execStep("INSERT INTO runtime_lease"), queryStep("WITH ready"), terminalStep("commit"), queryStep("SELECT r.id,r.conversation_id,r.status")}
	r = runsFixture(t, steps...)
	locks, e := coordination.New(r.Pool)
	if e != nil {
		t.Fatal(e)
	}
	defer locks.Close(context.Background())
	r.Coordinator = locks
	r.Enabled = true
	if e := r.Recover(context.Background()); e != nil {
		t.Fatal(e)
	}
}
