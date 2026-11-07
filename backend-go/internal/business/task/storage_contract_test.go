package task

import (
	"context"
	"database/sql/driver"
	"errors"
	"testing"
	"time"
)

const contractID = "10000000-0000-4000-8000-000000000001"

func taskRow() []driver.Value {
	now := time.Now().UTC()
	return sqlValues(contractID, "任务", nil, "TODO", "MEDIUM", now, nil, nil, now, now, nil, nil, "NONE", "UTC", 0, false)
}
func reminderSync() []sqlStep {
	return []sqlStep{execStep("DELETE FROM reminder"), execStep("UPDATE reminder")}
}
func TestTaskStorageWritesContract(t *testing.T) {
	for _, mode := range []string{"insert", "update", "delete", "deleteAll", "notDeleted", "missingUpdate"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin")}
			switch mode {
			case "insert":
				steps = append(steps, queryStep("INSERT INTO task", taskRow()), execStep("DELETE FROM task_tag"), execStep("INSERT INTO task_tag"))
			case "update":
				steps = append(steps, queryStep("UPDATE task", taskRow()), execStep("DELETE FROM task_tag"), execStep("INSERT INTO task_tag"))
				steps = append(steps, reminderSync()...)
			case "delete", "deleteAll":
				steps = append(steps, execStep("DELETE FROM task"))
				steps = append(steps, reminderSync()...)
			case "notDeleted":
				q := execStep("DELETE FROM task")
				q.affected = 0
				steps = append(steps, q)
			case "missingUpdate":
				steps = append(steps, queryStep("UPDATE task"))
			}
			if mode == "missingUpdate" {
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, terminalStep("commit"))
			}
			s, e := NewPostgresStore(contractPool(t, steps...))
			if e != nil {
				t.Fatal(e)
			}
			ctx := context.Background()
			v := Task{ID: contractID, Tags: []string{"标签"}}
			switch mode {
			case "insert":
				v, e = s.Insert(ctx, v)
			case "update", "missingUpdate":
				v, e = s.Update(ctx, v)
			case "delete", "notDeleted":
				var ok bool
				ok, e = s.Delete(ctx, contractID)
				if ok != (mode == "delete") {
					t.Fatal(ok)
				}
			case "deleteAll":
				var n int
				n, e = s.DeleteAll(ctx, []string{contractID})
				if n != 1 {
					t.Fatal(n)
				}
			}
			if (e != nil) != (mode == "missingUpdate") {
				t.Fatal(e)
			}
			if (mode == "insert" || mode == "update") && (len(v.Tags) != 1 || v.ID != contractID) {
				t.Fatal(v)
			}
		})
	}
	if _, e := NewPostgresStore(nil); e == nil {
		t.Fatal("nil pool")
	}
}
func TestTaskStorageQueriesContract(t *testing.T) {
	for _, mode := range []string{"get", "missing", "search", "overflow", "export", "hits", "due", "all", "ids"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			if mode == "search" || mode == "overflow" {
				q := queryStep("count(*)", sqlValues(1))
				q.check = func(a []driver.NamedValue) {
					if a[1].Value != `%a\_\%%` {
						t.Fatal(a)
					}
				}
				steps = append(steps, q)
			}
			if mode != "overflow" {
				q := queryStep("SELECT", taskRow())
				if mode == "missing" {
					q.rows = nil
				}
				steps = append(steps, q)
				if mode != "missing" {
					if mode == "get" {
						steps = append(steps, queryStep("SELECT tag FROM task_tag", sqlValues("标签")))
					} else {
						steps = append(steps, queryStep("SELECT task_id::text,tag", sqlValues(contractID, "标签")))
					}
				}
			}
			s, _ := NewPostgresStore(contractPool(t, steps...))
			ctx := context.Background()
			var e error
			var items []Task
			switch mode {
			case "get", "missing":
				var v Task
				var ok bool
				v, ok, e = s.Get(ctx, contractID)
				if ok != (mode == "get") {
					t.Fatal(ok)
				}
				if ok {
					items = []Task{v}
				}
			case "search", "overflow":
				q := Query{Keyword: " A_% ", Page: 0, Size: 10}
				if mode == "overflow" {
					q.Page = 1 << 30
				}
				var p Page
				p, e = s.Search(ctx, q)
				items = p.Items
				if p.Total != 1 {
					t.Fatal(p)
				}
			case "export":
				items, e = s.ExportBatch(ctx, 0, 10)
			case "hits":
				items, e = s.SearchHits(ctx, "A", 10)
			case "due":
				items, e = s.DueBetween(ctx, time.Now(), time.Now())
			case "all":
				items, e = s.ListAll(ctx)
			case "ids":
				items, e = s.FindByIDs(ctx, []string{contractID})
			}
			if e != nil {
				t.Fatal(e)
			}
			if mode != "missing" && mode != "overflow" && (len(items) != 1 || len(items[0].Tags) != 1) {
				t.Fatal(items)
			}
		})
	}
	s, _ := NewPostgresStore(contractPool(t, queryStep("FILTER", sqlValues(4, 2)), queryStep("DISTINCT tag", sqlValues("标签")), queryStep("count(*)", sqlValues(4))))
	ctx := context.Background()
	if v, e := s.Stats(ctx, time.Now()); e != nil || v.Pending != 4 || v.Overdue != 2 {
		t.Fatal(v, e)
	}
	if v, e := s.ListTags(ctx); e != nil || len(v) != 1 {
		t.Fatal(v, e)
	}
	if v, e := s.CountAll(ctx); e != nil || v != 4 {
		t.Fatal(v, e)
	}
	if v, e := s.FindByIDs(ctx, nil); e != nil || len(v) != 0 {
		t.Fatal(v, e)
	}
}
func TestTaskStorageReadFailureContract(t *testing.T) {
	for _, op := range []string{"search", "tags", "all"} {
		for _, phase := range []string{"query", "scan", "iteration"} {
			t.Run(op+phase, func(t *testing.T) {
				q := queryStep("SELECT")
				if phase == "query" {
					q.err = errors.New("query")
				}
				if phase == "scan" {
					q.rows = [][]driver.Value{sqlValues(nil, nil)}
				}
				if phase == "iteration" {
					q.rowErr = errors.New("iterator")
				}
				steps := []sqlStep{}
				if op == "search" {
					steps = append(steps, queryStep("count(*)", sqlValues(1)))
				}
				steps = append(steps, q)
				s, _ := NewPostgresStore(contractPool(t, steps...))
				var e error
				switch op {
				case "search":
					_, e = s.Search(context.Background(), Query{})
				case "tags":
					_, e = s.ListTags(context.Background())
				default:
					_, e = s.ListAll(context.Background())
				}
				if e == nil {
					t.Fatal("hidden failure")
				}
			})
		}
	}
}
func TestTaskWorkspaceStorageAndServiceContract(t *testing.T) {
	now := time.Now().UTC()
	row := sqlValues(contractID, "项目", nil, false, now, now)
	steps := []sqlStep{queryStep("FROM project", row), queryStep("INSERT INTO project", row), queryStep("UPDATE project", row), terminalStep("begin"), queryStep("count(*)", sqlValues(1)), queryStep("SELECT id,conversation_id", sqlValues("run", "contract", "会话", "INTERRUPTED", now)), terminalStep("commit")}
	store, _ := NewPostgresStore(contractPool(t, steps...))
	s, _ := NewService(store)
	ctx := context.Background()
	if p, e := s.Projects(ctx); e != nil || len(p) != 1 {
		t.Fatal(p, e)
	}
	name := " 项目 "
	if p, e := s.SaveProject(ctx, "", ProjectChange{Name: &name}); e != nil || p.ID != contractID {
		t.Fatal(p, e)
	}
	archived := true
	if _, e := s.SaveProject(ctx, contractID, ProjectChange{Archived: &archived}); e != nil {
		t.Fatal(e)
	}
	p, e := s.UnfinishedRuns(ctx)
	if e != nil || p.Total != 1 || p.Items[0].Status != "INTERRUPTED" {
		t.Fatal(p, e)
	}
}
