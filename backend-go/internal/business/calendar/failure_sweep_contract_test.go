package calendar

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
	"time"
)

// Every injected failure uses the same successful SQL contract up to the failing
// boundary. The remaining contract permits only rollback, never another write.
func TestCalendarStorageFailuresAbortEachPublicOperation(t *testing.T) {
	ctx := context.Background()
	at := calendarAt()
	end := at.Add(time.Hour)
	title := "日程"
	content := icsFixture("")
	cases := []struct {
		name  string
		steps []sqlStep
		run   func(*Events) error
	}{
		{"create", []sqlStep{queryStep("INSERT INTO calendar_event", calendarRow())}, func(s *Events) error {
			_, e := s.Create(ctx, EventRequest{Title: &title, StartsAt: &at, EndsAt: &end})
			return e
		}},
		{"update", []sqlStep{terminalStep("begin"), queryStep("FOR UPDATE", calendarRow()), queryStep("UPDATE calendar_event", calendarRow()), execStep("DELETE FROM reminder"), terminalStep("commit")}, func(s *Events) error { _, e := s.Update(ctx, calendarID, EventRequest{Title: &title}); return e }},
		{"delete", []sqlStep{terminalStep("begin"), queryStep("FOR UPDATE", sqlValues(calendarID)), execStep("DELETE FROM reminder"), execStep("DELETE FROM calendar_event"), terminalStep("commit")}, func(s *Events) error { _, e := s.Delete(ctx, calendarID); return e }},
		{"scan", []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("FROM calendar_event", calendarRow()), queryStep("FROM task", sqlValues(calendarID, "待办", at)), execStep("DELETE FROM reminder"), execStep("UPDATE reminder"), execStep("INSERT INTO reminder"), execStep("INSERT INTO reminder"), terminalStep("commit")}, func(s *Events) error { _, e := s.Scan(ctx, at); return e }},
		{"reminders", []sqlStep{queryStep("WHERE status=$1", reminderRow())}, func(s *Events) error { _, e := s.Reminders(ctx, "", true, 10, at); return e }},
		{"read reminder", []sqlStep{queryStep("UPDATE reminder", reminderRow())}, func(s *Events) error { _, e := s.MarkRead(ctx, calendarID, at); return e }},
		{"preview", []sqlStep{queryStep("calendar_ics_identity", sqlValues(false))}, func(s *Events) error { _, e := s.PreviewICS(ctx, content); return e }},
		{"import", []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("calendar_ics_identity", sqlValues(false)), queryStep("INSERT INTO calendar_event", sqlValues(calendarID)), execStep("INSERT INTO calendar_ics_identity"), terminalStep("commit")}, func(s *Events) error { _, e := s.ImportICS(ctx, content, icsDigest(content)); return e }},
		{"export", []sqlStep{queryStep("LIMIT 10001", calendarRow()), queryStep("event_id::text,uid", sqlValues(calendarID, "test-1"))}, func(s *Events) error { _, e := s.ExportICS(ctx); return e }},
		{"conflicts", []sqlStep{queryStep("LIMIT 10001", calendarRow())}, func(s *Events) error { _, e := s.conflicts(ctx, at, end); return e }},
	}
	for _, tc := range cases {
		for i, target := range tc.steps {
			if target.op == "rollback" {
				continue
			}
			for _, mode := range []string{"statement", "rows", "scan"} {
				if mode != "statement" && target.op != "query" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", tc.name, i, mode), func(t *testing.T) {
					failure := errors.New("injected calendar storage fault")
					steps := append([]sqlStep{}, tc.steps[:i+1]...)
					switch mode {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					inTx := false
					for _, step := range tc.steps[:i] {
						if step.op == "begin" {
							inTx = true
						}
						if step.op == "commit" {
							inTx = false
						}
					}
					if inTx && target.op != "commit" {
						steps = append(steps, terminalStep("rollback"))
					}
					s := calendarFixture(t, steps...)
					if e := tc.run(s); e == nil {
						t.Fatal("storage failure swallowed", tc.name, i, mode)
					}
				})
			}
		}
	}
}

func TestConflictWindowCapsAndLegacyDurations(t *testing.T) {
	at := calendarAt()
	ctx := context.Background()
	for _, kind := range []string{"zero", "year", "many", "definitions", "truncated"} {
		t.Run(kind, func(t *testing.T) {
			row := calendarRow()
			rows := [][]driver.Value{row}
			switch kind {
			case "zero":
				row[6] = at
			case "year":
				row[6] = at.Add(367 * 24 * time.Hour)
			case "many":
				for len(rows) < 1001 {
					rows = append(rows, calendarRow())
				}
			case "definitions":
				for len(rows) < 10001 {
					rows = append(rows, calendarRow())
				}
			case "truncated":
				for len(rows) < 16 {
					rows = append(rows, calendarRow())
				}
			}
			s := calendarFixture(t, queryStep("LIMIT 10001", rows...))
			out, e := s.conflicts(ctx, at, at.Add(time.Hour))
			if kind == "year" || kind == "many" || kind == "definitions" {
				if e == nil {
					t.Fatal("unsafe window accepted")
				}
				return
			}
			if e != nil {
				t.Fatal(e)
			}
			v := out.(map[string]any)
			if kind == "zero" && v["total"] != 0 {
				t.Fatal(out)
			}
			if kind == "truncated" && (v["total"] != 120 || v["truncated"] != true || len(v["items"].([]Conflict)) != 100) {
				t.Fatal(out)
			}
		})
	}
}
