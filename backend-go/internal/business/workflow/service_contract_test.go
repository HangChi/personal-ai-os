package workflow

import (
	"context"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"
	"time"
)

func defRow() []driver.Value {
	return sqlValues("w", "晨报", "KNOWLEDGE_REVIEW", "UTC", "09:00", 1, true, 3, 3, 1000, time.Now().UTC())
}
func executionRow() []driver.Value {
	return sqlValues("e", "w", time.Now().UTC(), "MANUAL", "COMPLETED", 1, 0, "只读报告", nil, nil, nil)
}
func executionSteps() []sqlStep {
	return []sqlStep{queryStep("WHERE e.id=$1", executionRow()), queryStep("FROM workflow_attempt", sqlValues("e", 1, "COMPLETED", time.Now(), time.Now(), nil))}
}
func TestWorkflowSaveAndReadContract(t *testing.T) {
	for _, mode := range []string{"create", "update"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock")}
			id := ""
			match := "INSERT INTO workflow"
			if mode == "create" {
				steps = append(steps, queryStep("count(*)", sqlValues(0)))
			} else {
				id = "w"
				match = "UPDATE workflow"
				steps = append(steps, queryStep("WHERE id=$1", defRow()))
			}
			steps = append(steps, queryStep("pg_timezone_names", sqlValues(true)), queryStep(match, defRow()), terminalStep("commit"), queryStep("WHERE id=$1", defRow()), queryStep("count(*)", sqlValues(1)), queryStep("ORDER BY created_at", defRow()))
			s := &Service{Pool: contractPool(t, steps...)}
			name, kind, zone, local := " 晨报 ", "KNOWLEDGE_REVIEW", "UTC", "09:00"
			weekday, maxRuns, maxItems, maxChars, enabled := 1, 3, 3, 1000, true
			v, e := s.Save(context.Background(), id, Change{&name, &kind, &zone, &local, &weekday, &enabled, &maxRuns, &maxItems, &maxChars})
			if e != nil || v.ID != "w" {
				t.Fatal(v, e)
			}
			if _, e = s.Get(context.Background(), "w"); e != nil {
				t.Fatal(e)
			}
			if _, e = s.List(context.Background()); e != nil {
				t.Fatal(e)
			}
		})
	}
}
func TestWorkflowSaveValidationAndRollback(t *testing.T) {
	for _, mode := range []string{"limit", "missing", "name", "time", "zone", "databaseZone", "write"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock")}
			id, name, local, zone := "", "晨报", "09:00", "UTC"
			if mode == "missing" {
				id = "missing"
				steps = append(steps, queryStep("WHERE id=$1"))
			} else {
				n := 0
				if mode == "limit" {
					n = 100
				}
				steps = append(steps, queryStep("count(*)", sqlValues(n)))
			}
			switch mode {
			case "name":
				name = " "
			case "time":
				local = "29:00"
			case "zone":
				zone = "INVALID/ZONE"
			case "databaseZone":
				steps = append(steps, queryStep("pg_timezone_names", sqlValues(false)))
			case "write":
				q := queryStep("INSERT INTO workflow")
				q.err = errors.New("write")
				steps = append(steps, queryStep("pg_timezone_names", sqlValues(true)), q)
			}
			steps = append(steps, terminalStep("rollback"))
			s := &Service{Pool: contractPool(t, steps...)}
			if _, e := s.Save(context.Background(), id, Change{Name: &name, LocalTime: &local, Timezone: &zone}); e == nil {
				t.Fatal("accepted")
			}
		})
	}
}
func TestWorkflowExecutionRunRetryHistoryContract(t *testing.T) {
	for _, mode := range []string{"execution", "run", "retry", "history"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			switch mode {
			case "run":
				steps = append(steps, queryStep("FROM workflow WHERE", defRow()), queryStep("enqueue_workflow", sqlValues("e")))
			case "retry":
				steps = append(steps, terminalStep("begin"), queryStep("status,attempts", sqlValues("FAILED", 2)), execStep("status='PENDING'"), terminalStep("commit"))
			case "history":
				steps = append(steps, queryStep("FROM workflow WHERE", defRow()), queryStep("count(*)", sqlValues(1)), queryStep("WHERE e.workflow_id", executionRow()))
			}
			if mode == "history" {
				steps = append(steps, executionSteps()[1])
			} else {
				steps = append(steps, executionSteps()...)
			}
			s := &Service{Pool: contractPool(t, steps...)}
			ctx := context.Background()
			var e error
			var v Execution
			switch mode {
			case "run":
				v, e = s.Run(ctx, "w")
			case "retry":
				v, e = s.Retry(ctx, "e")
			case "history":
				_, e = s.History(ctx, "w")
			default:
				v, e = s.Execution(ctx, "e")
			}
			if e != nil {
				t.Fatal(e)
			}
			if mode != "history" && (len(v.History) != 1 || v.ModelCalls != 0) {
				t.Fatal(v)
			}
		})
	}
	for _, mode := range []string{"missing", "busy", "exhausted"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("status,attempts")
			if mode == "busy" {
				q.rows = [][]driver.Value{sqlValues("RUNNING", 1)}
			}
			if mode == "exhausted" {
				q.rows = [][]driver.Value{sqlValues("FAILED", 3)}
			}
			s := &Service{Pool: contractPool(t, terminalStep("begin"), q, terminalStep("rollback"))}
			if _, e := s.Retry(context.Background(), "e"); e == nil {
				t.Fatal("unsafe retry")
			}
		})
	}
}
func TestWorkflowReadOnlyReportAndWorkerContract(t *testing.T) {
	ctx := context.Background()
	now := time.Now().UTC()
	d := Snapshot{Name: "巡检", Kind: "KNOWLEDGE_REVIEW", Timezone: "UTC", MaxItems: 2, MaxOutputChars: 1000}
	for _, mode := range []string{"report", "truncate", "empty", "worker", "malformed", "unclaimed", "queryError"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			if mode == "worker" {
				steps = append(steps, queryStep("claim_workflow", sqlValues("e", "token", `{"Name":"巡检","Kind":"KNOWLEDGE_REVIEW","Timezone":"UTC","max_items":2,"max_output_chars":1000}`, now)))
			}
			if mode == "malformed" {
				steps = append(steps, queryStep("claim_workflow", sqlValues("e", "t", "bad", now)))
			}
			if mode == "unclaimed" {
				steps = append(steps, queryStep("claim_workflow"))
			}
			if mode == "queryError" {
				q := queryStep("claim_workflow")
				q.err = errors.New("claim")
				steps = append(steps, q)
			}
			if mode == "report" || mode == "truncate" || mode == "worker" {
				steps = append(steps, queryStep("FROM knowledge_document", sqlValues(strings.Repeat("资料", 200), now), sqlValues("第二份", now)), queryStep("FROM knowledge_capture", sqlValues("https://fixture.invalid", "失败"), sqlValues("https://fixture.invalid/2", "正在索引")))
			}
			if mode == "empty" {
				steps = append(steps, queryStep("FROM knowledge_document"), queryStep("FROM knowledge_capture"))
			}
			if mode == "worker" || mode == "malformed" {
				finish := queryStep("finish_workflow", sqlValues(true))
				finish.check = func(a []driver.NamedValue) {
					if mode == "malformed" && a[3].Value == nil {
						t.Fatal("failure hidden")
					}
					if mode == "worker" && a[3].Value != nil {
						t.Fatal(a)
					}
				}
				steps = append(steps, finish)
			}
			s := &Service{Pool: contractPool(t, steps...)}
			if mode == "worker" || mode == "malformed" || mode == "unclaimed" || mode == "queryError" {
				work, e := s.RunNext(ctx)
				if work != (mode == "worker" || mode == "malformed") || (e != nil) != (mode == "queryError") {
					t.Fatal(work, e)
				}
				return
			}
			dd := d
			if mode == "truncate" {
				dd.MaxOutputChars = 200
			}
			report, e := s.Report(ctx, dd, now)
			if e != nil {
				t.Fatal(e)
			}
			if mode == "empty" && !strings.Contains(report, "暂无匹配") {
				t.Fatal(report)
			}
			if mode == "truncate" && (len([]rune(report)) != 200 || !strings.Contains(report, "达到输出上限")) {
				t.Fatal(report)
			}
			if mode == "report" && !strings.Contains(report, "达到数据项上限") {
				t.Fatal(report)
			}
		})
	}
	s := &Service{Pool: contractPool(t, execStep("dispatch_workflows"))}
	if e := s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Report(ctx, Snapshot{Timezone: "bad/zone"}, now); e == nil {
		t.Fatal("zone")
	}
}
