package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
)

func TestRunInteractionStorageFaultsStopAtBoundary(t *testing.T) {
	ctx := context.Background()
	read := func(status string) []sqlStep { return runReadSteps(status, false) }
	mutation := func(status string, tail ...sqlStep) []sqlStep {
		return append(append(runMutateSteps(), queryStep("FROM chat_run WHERE conversation_id", runRow(status))), tail...)
	}
	cases := []struct {
		name  string
		steps []sqlStep
		run   func(*Runs) error
	}{
		{"get", read("INTERRUPTED"), func(r *Runs) error { _, e := r.Get(ctx, "contract", "run", 0); return e }},
		{"stop", append(append(read("RUNNING"), runMutateSteps()...), execStep("SET status='STOPPED'"), terminalStep("commit")), func(r *Runs) error { return r.Stop(ctx, "contract", "run") }},
		{"finish", append(mutation("WAITING_CONFIRMATION", queryStep("FROM confirmation", sqlValues(false)), execStep("SET status='COMPLETED'"), terminalStep("commit")), read("COMPLETED")...), func(r *Runs) error { _, e := r.FinishConfirmations(ctx, "contract", "run"); return e }},
		{"answer", append(mutation("WAITING_USER", queryStep("FROM agent_question", questionRow("PENDING", nil)), execStep("UPDATE agent_question"), queryStep("FROM confirmation", sqlValues(true)), queryStep("FROM operation_record", sqlValues(false)), execStep("UPDATE chat_run SET status=$2"), terminalStep("commit")), read("WAITING_CONFIRMATION")...), func(r *Runs) error { _, e := r.Answer(ctx, "contract", "run", "question", "答案"); return e }},
		{"followup", append(mutation("COMPLETED", queryStep("FROM agent_followup WHERE"), queryStep("SELECT EXISTS(SELECT 1 FROM agent_followup", sqlValues(false)), queryStep("SELECT EXISTS(SELECT 1 FROM chat_run", sqlValues(false)), execStep("INSERT INTO agent_followup"), queryStep("FROM agent_question", questionRow("PENDING", nil)), queryStep("FROM confirmation", sqlValues(true)), terminalStep("commit")), read("COMPLETED")...), func(r *Runs) error {
			_, e := r.Followup(ctx, "contract", "run", "input", "STEER", StreamRequest{Content: "补充"})
			return e
		}},
	}
	for _, action := range []string{"edit", "cancel", "steer"} {
		steps := mutation("COMPLETED", queryStep("FROM agent_followup WHERE", followupRow("QUEUE", "PENDING", "补充")))
		switch action {
		case "edit":
			steps = append(steps, execStep("SET request=$2"))
		case "cancel":
			steps = append(steps, execStep("SET status='CANCELLED'"))
		case "steer":
			steps = append(steps, queryStep("ORDER BY created_at DESC", runRow("COMPLETED")), queryStep("SELECT id FROM chat_run", sqlValues("run")), queryStep("FROM operation_record", sqlValues(false)), execStep("SET mode='STEER'"), queryStep("FROM agent_question"), queryStep("FROM confirmation", sqlValues(false)))
		}
		steps = append(steps, terminalStep("commit"))
		steps = append(steps, read("COMPLETED")...)
		verb := action
		cases = append(cases, struct {
			name  string
			steps []sqlStep
			run   func(*Runs) error
		}{action, steps, func(r *Runs) error {
			_, e := r.ChangeFollowup(ctx, "contract", "run", "input", verb, "新要求")
			return e
		}})
	}
	for _, tc := range cases {
		t.Run(tc.name+"/success", func(t *testing.T) {
			if e := tc.run(runsFixture(t, tc.steps...)); e != nil {
				t.Fatal(e)
			}
		})
		for i, target := range tc.steps {
			for _, fault := range []string{"statement", "rows", "scan"} {
				if fault != "statement" && target.op != "query" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", tc.name, i, fault), func(t *testing.T) {
					failure := errors.New("run storage fault")
					steps := append([]sqlStep{}, tc.steps[:i+1]...)
					switch fault {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					inTx := false
					for _, s := range tc.steps[:i] {
						if s.op == "begin" {
							inTx = true
						}
						if s.op == "commit" {
							inTx = false
						}
					}
					if inTx && target.op != "commit" {
						steps = append(steps, terminalStep("rollback"))
					}
					if e := tc.run(runsFixture(t, steps...)); e == nil {
						t.Fatal("storage fault swallowed")
					}
				})
			}
		}
	}
}
