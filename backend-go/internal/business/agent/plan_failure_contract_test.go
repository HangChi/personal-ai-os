package agent

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
)

func TestPlanSQLFailuresRollbackBeforeLaterWrites(t *testing.T) {
	create := append(planReadSteps(true, stepRow(1, "RUNNING")), execStep("status='ABORTED'"), execStep("INSERT INTO agent_plan("), execStep("INSERT INTO agent_plan_step"), terminalStep("commit"))
	change := append(planReadSteps(true, stepRow(1, "PENDING")), execStep("UPDATE agent_plan_step SET"), execStep("UPDATE agent_plan SET"), terminalStep("commit"))
	for _, tc := range []struct {
		name   string
		steps  []sqlStep
		invoke func(*Plans) error
	}{
		{"create", create, func(p *Plans) error {
			_, e := p.Create(context.Background(), "c", "goal", []string{"step"}, false)
			return e
		}},
		{"change", change, func(p *Plans) error { _, e := p.Change(context.Background(), "c", 1, "start", ""); return e }},
		{"success", append(planReadSteps(true, stepRow(1, "RUNNING")), execStep("UPDATE agent_plan_step"), execStep("UPDATE agent_plan SET"), terminalStep("commit")), func(p *Plans) error { return p.Success(context.Background(), "c", "lookup", "receipt") }},
		{"failure", append(planReadSteps(true, stepRow(1, "RUNNING")), execStep("UPDATE agent_plan_step"), execStep("UPDATE agent_plan SET"), terminalStep("commit")), func(p *Plans) error { _, e := p.Failure(context.Background(), "c", "lookup", "unavailable"); return e }},
		{"read", append(planReadSteps(false, stepRow(1, "PENDING")), terminalStep("commit")), func(p *Plans) error { _, e := p.Get(context.Background(), "c", true); return e }},
	} {

		for i, target := range tc.steps {
			for _, fault := range []string{"statement", "rows", "scan"} {
				if fault != "statement" && target.op != "query" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", tc.name, i, fault), func(t *testing.T) {
					failure := errors.New("planned SQL failure")
					steps := append([]sqlStep(nil), tc.steps[:i+1]...)
					switch fault {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					if i > 0 && steps[i].op != "commit" {
						steps = append(steps, terminalStep("rollback"))
					}
					p := &Plans{Pool: contractPool(t, steps...)}
					if e := tc.invoke(p); e == nil {
						t.Fatal("storage failure swallowed")
					}
				})
			}
		}
	}
}

func TestPlanReadRejectsCorruptStepRowsAndMissingActivePlan(t *testing.T) {
	for _, action := range []string{"change", "success", "failure"} {
		t.Run(action, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), queryStep("SELECT conversation_id", sqlValues("c")), queryStep("FROM agent_plan WHERE")}
			end := "commit"
			if action == "change" {
				end = "rollback"
			}
			p := &Plans{Pool: contractPool(t, append(steps, terminalStep(end))...)}
			switch action {
			case "change":
				if _, e := p.Change(context.Background(), "c", 1, "start", ""); e == nil {
					t.Fatal("missing plan changed")
				}
			case "success":
				if e := p.Success(context.Background(), "c", "lookup", "ok"); e != nil {
					t.Fatal(e)
				}
			case "failure":
				if message, e := p.Failure(context.Background(), "c", "lookup", "failed"); e != nil || message != "" {
					t.Fatal(message, e)
				}
			}
		})
	}
	steps := append(planReadSteps(false, sqlValues("invalid sequence", "step", "PENDING", nil, nil, nil, nil, nil, nil)), terminalStep("rollback"))
	p := &Plans{Pool: contractPool(t, steps...)}
	if _, e := p.Get(context.Background(), "c", true); e == nil {
		t.Fatal("corrupt row accepted")
	}
}
