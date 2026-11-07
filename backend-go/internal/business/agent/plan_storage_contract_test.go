package agent

import (
	"context"
	"database/sql/driver"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

func planRow() []driver.Value {
	return sqlValues("p", "c", "完成目标", "ACTIVE", time.Now(), time.Now())
}
func stepRow(seq int, status string) []driver.Value {
	return sqlValues(seq, "步骤", status, "验收标准", nil, nil, nil, nil, nil)
}
func planReadSteps(lock bool, rows ...[]driver.Value) []sqlStep {
	steps := []sqlStep{terminalStep("begin")}
	if lock {
		steps = append(steps, queryStep("SELECT conversation_id", sqlValues("c")))
	}
	return append(steps, queryStep("FROM agent_plan WHERE", planRow()), queryStep("FROM agent_plan_step", rows...))
}
func TestAgentPlanCreateAndReviseStorageContract(t *testing.T) {
	for _, mode := range []string{"create", "revise"} {
		t.Run(mode, func(t *testing.T) {
			steps := planReadSteps(true, stepRow(1, "RUNNING"))
			steps = append(steps, execStep("status='ABORTED'"), execStep("INSERT INTO agent_plan("), execStep("INSERT INTO agent_plan_step"), execStep("INSERT INTO agent_plan_step"), terminalStep("commit"))
			s := &Plans{Pool: contractPool(t, steps...)}
			goal := " 目标 "
			if mode == "revise" {
				goal = " "
			}
			p, e := s.Create(context.Background(), "c", goal, []string{" 一 ", "二"}, mode == "revise")
			if e != nil || p.Status != "ACTIVE" || len(p.Steps) != 2 || p.Steps[0].Title != "一" || p.ID == "" {
				t.Fatal(p, e)
			}
			if mode == "revise" && p.Goal != "完成目标" {
				t.Fatal(p)
			}
		})
	}
}
func TestAgentPlanChangeStateMachineContract(t *testing.T) {
	for _, tc := range []struct {
		name, status, action, note string
		seq                        int
		want                       string
		mutate, fail               bool
	}{
		{"start", "PENDING", "start", "", 1, "RUNNING", true, false}, {"restart", "FAILED", "start", "", 1, "RUNNING", true, false}, {"idempotentStart", "RUNNING", "start", "", 1, "RUNNING", false, false}, {"done", "PENDING", "complete", "可核对结论", 1, "DONE", true, false}, {"duplicateDone", "DONE", "complete", "", 1, "DONE", false, false}, {"fail", "RUNNING", "fail", "", 1, "FAILED", true, false}, {"skip", "PENDING", "skip", "无需执行", 1, "SKIPPED", true, false}, {"outOfOrderStart", "PENDING", "start", "", 2, "", false, true}, {"outOfOrderComplete", "PENDING", "complete", "结果", 2, "", false, true}, {"failedComplete", "FAILED", "complete", "结果", 1, "", false, true}, {"emptyResult", "RUNNING", "complete", "", 1, "", false, true}, {"terminalStart", "DONE", "start", "", 1, "", false, true}, {"terminalFail", "DONE", "fail", "", 1, "", false, true}, {"terminalSkip", "SKIPPED", "skip", "", 1, "", false, true}, {"skippedComplete", "SKIPPED", "complete", "结果", 1, "", false, true}, {"invalidAction", "PENDING", "bad", "", 1, "", false, true}, {"invalidSeq", "PENDING", "start", "", 0, "", false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			steps := planReadSteps(true, stepRow(1, tc.status), stepRow(2, "PENDING"))
			if tc.mutate {
				q := execStep("UPDATE agent_plan_step SET")
				q.check = func(a []driver.NamedValue) {
					if a[2].Value != tc.want {
						t.Fatal(a)
					}
				}
				steps = append(steps, q, execStep("UPDATE agent_plan SET"))
			}
			end := "commit"
			if tc.fail {
				end = "rollback"
			}
			steps = append(steps, terminalStep(end))
			s := &Plans{Pool: contractPool(t, steps...)}
			p, e := s.Change(context.Background(), "c", tc.seq, tc.action, tc.note)
			if (e != nil) != tc.fail {
				t.Fatal(p, e)
			}
			if !tc.fail && p.Steps[tc.seq-1].Status != tc.want {
				t.Fatal(p)
			}
		})
	}
}
func TestAgentPlanToolRecordingAndPromptContract(t *testing.T) {
	for _, mode := range []string{"success", "failure", "prompt", "truncated", "complete"} {
		t.Run(mode, func(t *testing.T) {
			rows := [][]driver.Value{stepRow(1, "DONE"), stepRow(2, "RUNNING"), stepRow(3, "PENDING"), stepRow(4, "PENDING"), stepRow(5, "PENDING"), stepRow(6, "PENDING"), stepRow(7, "PENDING"), stepRow(8, "PENDING")}
			if mode == "complete" {
				rows = [][]driver.Value{stepRow(1, "DONE")}
			}
			write := mode == "success" || mode == "failure"
			steps := planReadSteps(write, rows...)
			if write {
				steps = append(steps, execStep("UPDATE agent_plan_step"), execStep("UPDATE agent_plan SET"))
			}
			steps = append(steps, terminalStep("commit"))
			s := &Plans{Pool: contractPool(t, steps...), Properties: config.PlanProperties{Enabled: true, MaxInjectChars: 10000}}
			ctx := context.Background()
			if write {
				if mode == "success" {
					if e := s.Success(ctx, "c", "lookup", "结果"); e != nil {
						t.Fatal(e)
					}
				} else {
					hint, e := s.Failure(ctx, "c", "lookup", "失败")
					if e != nil || !strings.Contains(hint, "重启") {
						t.Fatal(hint, e)
					}
				}
				return
			}
			if mode == "truncated" {
				s.Properties.MaxInjectChars = 50
			}
			p, e := s.Prompt(ctx, "c")
			if e != nil || p == "" {
				t.Fatal(p, e)
			}
			if mode == "prompt" && (!strings.Contains(p, "第 2 步") || !strings.Contains(p, "等共 6 步")) {
				t.Fatal(p)
			}
			if mode == "complete" && !strings.Contains(p, "所有步骤已结束") {
				t.Fatal(p)
			}
			if mode == "truncated" && !strings.Contains(p, "已截断") {
				t.Fatal(p)
			}
		})
	}
}
func TestAgentAuditConfirmationLinkContract(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	steps := []sqlStep{terminalStep("begin"), queryStep("count(*)", sqlValues(2)), queryStep("request_digest,response_payload", sqlValues(1, "delete", "TOOL", "REQUIRES_CONFIRMATION", 4, "c", time.Now(), "turn", "digest", `{"confirmationId":"10000000-0000-4000-8000-000000000001"}`), sqlValues(2, "read", "TOOL", "SUCCESS", 2, "c", time.Now(), nil, "digest", nil)), queryStep("FROM confirmation", sqlValues(id, "c", "EXPIRED")), terminalStep("commit")}
	a := &Audit{Pool: contractPool(t, steps...)}
	p, e := a.Search(context.Background(), " c ", "", 0, 20)
	if e != nil || len(p.Items) != 2 || p.Items[0].ConfirmationStatus == nil || *p.Items[0].ConfirmationStatus != "EXPIRED" || p.Items[1].ConfirmationStatus != nil {
		t.Fatal(p, e)
	}
}
