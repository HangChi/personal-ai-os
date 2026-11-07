package task

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestTaskStorageFaultSweepStopsAfterFailure(t *testing.T) {
	ctx := context.Background()
	type operation struct {
		name  string
		steps []sqlStep
		run   func(*PostgresStore) error
	}
	cases := []operation{}
	for _, mode := range []string{"insert", "update", "delete", "deleteAll"} {
		steps := []sqlStep{terminalStep("begin")}
		switch mode {
		case "insert", "update":
			match := "INSERT INTO task"
			if mode == "update" {
				match = "UPDATE task"
			}
			steps = append(steps, queryStep(match, taskRow()), execStep("DELETE FROM task_tag"), execStep("INSERT INTO task_tag"))
			if mode == "update" {
				steps = append(steps, reminderSync()...)
			}
		default:
			steps = append(steps, execStep("DELETE FROM task"))
			steps = append(steps, reminderSync()...)
		}
		steps = append(steps, terminalStep("commit"))
		cases = append(cases, operation{mode, steps, func(s *PostgresStore) error {
			task := Task{ID: contractID, Tags: []string{"tag"}}
			switch mode {
			case "insert":
				_, e := s.Insert(ctx, task)
				return e
			case "update":
				_, e := s.Update(ctx, task)
				return e
			case "delete":
				_, e := s.Delete(ctx, contractID)
				return e
			default:
				_, e := s.DeleteAll(ctx, []string{contractID})
				return e
			}
		}})
	}
	for _, mode := range []string{"get", "search", "export", "hits", "due", "ids"} {
		steps := []sqlStep{}
		if mode == "search" {
			steps = append(steps, queryStep("count(*)", sqlValues(1)))
		}
		steps = append(steps, queryStep("SELECT", taskRow()))
		if mode == "get" {
			steps = append(steps, queryStep("SELECT tag FROM task_tag", sqlValues("tag")))
		} else {
			steps = append(steps, queryStep("SELECT task_id::text,tag", sqlValues(contractID, "tag")))
		}
		cases = append(cases, operation{mode, steps, func(s *PostgresStore) error {
			switch mode {
			case "get":
				_, _, e := s.Get(ctx, contractID)
				return e
			case "search":
				_, e := s.Search(ctx, Query{Size: 10})
				return e
			case "export":
				_, e := s.ExportBatch(ctx, 0, 10)
				return e
			case "hits":
				_, e := s.SearchHits(ctx, "task", 10)
				return e
			case "due":
				_, e := s.DueBetween(ctx, time.Now(), time.Now())
				return e
			default:
				_, e := s.FindByIDs(ctx, []string{contractID})
				return e
			}
		}})
	}

	for _, mode := range []string{"projects", "projectInsert", "projectUpdate", "runs"} {
		row := sqlValues(contractID, "project", nil, false, time.Unix(1, 0), time.Unix(1, 0))
		steps := []sqlStep{queryStep("FROM project", row)}
		switch mode {
		case "projectInsert":
			steps = []sqlStep{queryStep("INSERT INTO project", row)}
		case "projectUpdate":
			steps = []sqlStep{queryStep("UPDATE project", row)}
		case "runs":
			steps = []sqlStep{terminalStep("begin"), queryStep("SELECT count(*)", sqlValues(1)), queryStep("SELECT id,conversation_id", sqlValues("run", "conversation", "title", "INTERRUPTED", time.Unix(1, 0))), terminalStep("commit")}
		}
		cases = append(cases, operation{mode, steps, func(s *PostgresStore) error {
			switch mode {
			case "projects":
				_, e := s.Projects(ctx)
				return e
			case "projectInsert":
				_, e := s.SaveProject(ctx, "", ProjectChange{})
				return e
			case "projectUpdate":
				_, e := s.SaveProject(ctx, contractID, ProjectChange{})
				return e
			default:
				_, e := s.UnfinishedRuns(ctx)
				return e
			}
		}})
	}
	for _, tc := range cases {
		t.Run(tc.name+"/success", func(t *testing.T) {
			s, _ := NewPostgresStore(contractPool(t, tc.steps...))
			if e := tc.run(s); e != nil {
				t.Fatal(e)
			}
		})
		for i, target := range tc.steps {
			for _, fault := range []string{"statement", "rows", "scan"} {
				if fault != "statement" && target.op != "query" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", tc.name, i, fault), func(t *testing.T) {
					failure := errors.New("task storage fault")
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
					if len(tc.steps) > 0 && tc.steps[0].op == "begin" && i > 0 && target.op != "commit" {
						steps = append(steps, terminalStep("rollback"))
					}
					s, _ := NewPostgresStore(contractPool(t, steps...))
					if e := tc.run(s); e == nil {
						t.Fatal("storage failure swallowed")
					}
				})
			}
		}
	}
}
