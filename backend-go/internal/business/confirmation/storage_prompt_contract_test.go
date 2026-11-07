package confirmation

import (
	"context"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"
	"time"
)

func confirmationRow() []driver.Value {
	at := time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC)
	return sqlValues("id", "token", "conversation", "DELETE_TASK", "删除任务", "{}", 1, "PENDING", at, at.Add(time.Hour), nil)
}
func TestPostgresConfirmationStorageContract(t *testing.T) {
	ctx := context.Background()
	if _, e := NewPostgresStore(nil); e == nil {
		t.Fatal("nil pool")
	}
	p := contractPool(t, queryStep("INSERT INTO confirmation", sqlValues("id")), queryStep("WHERE id=", confirmationRow()), queryStep("WHERE id="), queryStep("WHERE status=", confirmationRow()), execStep("UPDATE confirmation"))
	s, e := NewPostgresStore(p)
	if e != nil {
		t.Fatal(e)
	}
	r, e := s.Create(ctx, Record{Token: "token", Action: DeleteTask})
	if e != nil || r.ID != "id" {
		t.Fatal(r, e)
	}
	r, ok, e := s.Get(ctx, "id")
	if e != nil || !ok || r.Summary != "删除任务" {
		t.Fatal(r, ok, e)
	}
	if _, ok, e = s.Get(ctx, "missing"); e != nil || ok {
		t.Fatal(ok, e)
	}
	list, e := s.List(ctx, "conversation", Pending)
	if e != nil || len(list) != 1 {
		t.Fatal(list, e)
	}
	if ok, e = s.CompareAndSet(ctx, "id", Pending, Confirmed, time.Now()); e != nil || !ok {
		t.Fatal(ok, e)
	}
	for _, mode := range []string{"query", "scan", "rows", "cas", "stale"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("SELECT")
			switch mode {
			case "query":
				q.err = errors.New("offline")
			case "scan":
				q.rows = [][]driver.Value{sqlValues(true)}
			case "rows":
				q.rowErr = errors.New("read")
			case "cas", "stale":
				q = execStep("UPDATE confirmation")
				if mode == "cas" {
					q.err = errors.New("write")
				} else {
					q.affected = 0
				}
			}
			s, _ := NewPostgresStore(contractPool(t, q))
			if mode == "cas" || mode == "stale" {
				ok, e := s.CompareAndSet(ctx, "id", Pending, Confirmed, time.Now())
				if ok || (e == nil) != (mode == "stale") {
					t.Fatal(ok, e)
				}
			} else {
				if _, e := s.List(ctx, "c", Pending); e == nil {
					t.Fatal("error hidden")
				}
			}
		})
	}
}
func TestConfirmationPromptSnapshotContract(t *testing.T) {
	now := time.Now().UTC()
	row := func(bucket int, status, summary string, total int64) []driver.Value {
		return sqlValues(bucket, "id", "DELETE_TASK", status, summary, now, total)
	}
	for _, mode := range []string{"blank", "empty", "pending", "recent", "query", "scan", "rows"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("WITH pending AS")
			switch mode {
			case "pending":
				q.rows = [][]driver.Value{row(0, "PENDING", strings.Repeat("😀", 100), 11), row(1, "CONFIRMED", "done", 0)}
			case "recent":
				q.rows = [][]driver.Value{row(1, "EXPIRED", "expired", 0)}
			case "query":
				q.err = errors.New("offline")
			case "scan":
				q.rows = [][]driver.Value{sqlValues(true)}
			case "rows":
				q.rowErr = errors.New("rows")
			}
			steps := []sqlStep{q}
			if mode == "blank" {
				steps = nil
			}
			s, _ := NewPostgresStore(contractPool(t, steps...))
			conv := "c"
			if mode == "blank" {
				conv = " "
			}
			p, e := s.Prompt(context.Background(), conv, now)
			if mode == "query" || mode == "scan" || mode == "rows" {
				if e == nil || p != "" {
					t.Fatal(p, e)
				}
				return
			}
			if e != nil {
				t.Fatal(e)
			}
			if mode == "blank" {
				if p != "" {
					t.Fatal(p)
				}
				return
			}
			if !strings.Contains(p, "仅为状态数据") && mode != "empty" {
				t.Fatal(p)
			}
			if mode == "pending" && (!strings.Contains(p, "11 项") || !strings.Contains(p, "CONFIRMED") || strings.Contains(p, strings.Repeat("😀", 91))) {
				t.Fatal(p)
			}
			if mode == "recent" && !strings.Contains(p, "EXPIRED") {
				t.Fatal(p)
			}
		})
	}
	if promptSummary("short") != "short" || len(promptSummary(strings.Repeat("a", 181))) != 180 {
		t.Fatal("summary bound")
	}
}
