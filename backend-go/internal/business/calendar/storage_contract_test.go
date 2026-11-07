package calendar

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

const calendarID = "10000000-0000-4000-8000-000000000001"

func calendarAt() time.Time { return time.Date(2026, 11, 7, 10, 0, 0, 0, time.UTC) }
func calendarRow() []driver.Value {
	at := calendarAt()
	return sqlValues(calendarID, "日程", nil, nil, false, at, at.Add(time.Hour), "NONE", nil, 15, at)
}
func calendarFixture(t *testing.T, steps ...sqlStep) *Events {
	return NewEvents(contractPool(t, steps...), config.CalendarProperties{DefaultQueryLimit: 20, MaxQueryLimit: 100, MaxRangeDays: 366, DefaultReminderLead: 15 * time.Minute, ScanLookback: time.Hour, ScanLookahead: time.Hour}, time.UTC)
}
func reminderRow() []driver.Value {
	at := calendarAt()
	return sqlValues(calendarID, "TASK", calendarID, "提醒", at, at.Add(-15*time.Minute), "PENDING", nil)
}
func TestCalendarEventPersistenceContract(t *testing.T) {
	ctx := context.Background()
	at := calendarAt()
	title := "日程"
	end := at.Add(time.Hour)
	steps := []sqlStep{queryStep("INSERT INTO calendar_event", calendarRow()), queryStep("FROM calendar_event WHERE", calendarRow()), terminalStep("begin"), queryStep("FOR UPDATE", calendarRow()), queryStep("UPDATE calendar_event SET", calendarRow()), execStep("DELETE FROM reminder"), terminalStep("commit"), queryStep("ORDER BY starts_at", calendarRow()), terminalStep("begin"), queryStep("FOR UPDATE", sqlValues(calendarID)), execStep("DELETE FROM reminder"), execStep("DELETE FROM calendar_event"), terminalStep("commit")}
	s := calendarFixture(t, steps...)
	v, e := s.Create(ctx, EventRequest{Title: &title, StartsAt: &at, EndsAt: &end})
	if e != nil || v.ID != calendarID {
		t.Fatal(v, e)
	}
	if _, e = s.Get(ctx, calendarID); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Update(ctx, calendarID, EventRequest{Title: &title}); e != nil {
		t.Fatal(e)
	}
	from, to := at.Add(-time.Hour), at.Add(time.Hour)
	p, e := s.Search(ctx, &from, &to, 0, 0)
	if e != nil || p.Total != 1 || p.Items[0].Title != title {
		t.Fatal(p, e)
	}
	if n, e := s.Delete(ctx, calendarID); e != nil || n != 1 {
		t.Fatal(n, e)
	}
}
func TestCalendarMissingAndReadFailuresContract(t *testing.T) {
	for _, mode := range []string{"get", "update", "delete", "query", "scan", "rows"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("SELECT")
			if mode == "query" {
				q.err = errors.New("query")
			}
			if mode == "scan" {
				q.rows = [][]driver.Value{sqlValues(true)}
			}
			if mode == "rows" {
				q.rowErr = errors.New("rows")
			}
			steps := []sqlStep{}
			if mode == "update" || mode == "delete" {
				steps = append(steps, terminalStep("begin"))
			}
			steps = append(steps, q)
			if mode == "update" || mode == "delete" {
				steps = append(steps, terminalStep("rollback"))
			}
			s := calendarFixture(t, steps...)
			var e error
			switch mode {
			case "get":
				_, e = s.Get(context.Background(), calendarID)
			case "update":
				_, e = s.Update(context.Background(), calendarID, EventRequest{})
			case "delete":
				_, e = s.Delete(context.Background(), calendarID)
			default:
				at, end := calendarAt(), calendarAt().Add(time.Hour)
				_, e = s.Search(context.Background(), &at, &end, 0, 10)
			}
			if e == nil {
				t.Fatal("error swallowed")
			}
		})
	}
}
func TestCalendarReminderSweepContract(t *testing.T) {
	at := calendarAt()
	for _, duplicate := range []bool{false, true} {
		name := "new"
		if duplicate {
			name = "duplicate"
		}
		t.Run(name, func(t *testing.T) {
			insert := execStep("INSERT INTO reminder")
			if duplicate {
				insert.affected = 0
			}
			steps := []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("FROM calendar_event", calendarRow()), queryStep("FROM task", sqlValues(calendarID, "待办", at)), execStep("DELETE FROM reminder"), execStep("UPDATE reminder"), insert, insert, terminalStep("commit"), queryStep("WHERE status=$1", reminderRow()), queryStep("UPDATE reminder", reminderRow())}
			s := calendarFixture(t, steps...)
			v, e := s.Scan(context.Background(), at)
			if e != nil || v.Created+v.Skipped != 2 || v.Pruned != 1 {
				t.Fatal(v, e)
			}
			if duplicate && v.Created != 0 {
				t.Fatal(v)
			}
			items, e := s.Reminders(context.Background(), "", true, 0, at)
			if e != nil || len(items) != 1 {
				t.Fatal(items, e)
			}
			if _, e = s.MarkRead(context.Background(), calendarID, at); e != nil {
				t.Fatal(e)
			}
		})
	}
}
func TestCalendarExchangeStorageContract(t *testing.T) {
	ctx := context.Background()
	content := icsFixture("")
	steps := []sqlStep{queryStep("calendar_ics_identity", sqlValues(false)), terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("calendar_ics_identity", sqlValues(false)), queryStep("INSERT INTO calendar_event", sqlValues(calendarID)), execStep("INSERT INTO calendar_ics_identity"), terminalStep("commit"), terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("calendar_ics_identity", sqlValues(true)), terminalStep("commit"), queryStep("LIMIT 10001", calendarRow()), queryStep("event_id::text,uid", sqlValues(calendarID, "test-1")), queryStep("LIMIT 10001", calendarRow(), calendarRow())}
	s := calendarFixture(t, steps...)
	p, e := s.PreviewICS(ctx, content)
	if e != nil || len(p.Events) != 1 || p.DuplicateCount != 0 {
		t.Fatal(p, e)
	}
	if _, e = s.ImportICS(ctx, content, "stale"); e == nil {
		t.Fatal("stale digest accepted")
	}
	v, e := s.ImportICS(ctx, content, p.Digest)
	if e != nil || v.Created != 1 {
		t.Fatal(v, e)
	}
	v, e = s.ImportICS(ctx, content, p.Digest)
	if e != nil || v.Skipped != 1 {
		t.Fatal(v, e)
	}
	out, e := s.ExportICS(ctx)
	if e != nil || !strings.Contains(out, "UID:test-1") {
		t.Fatal(out, e)
	}
	at := calendarAt()
	conflicts, e := s.conflicts(ctx, at, at.Add(time.Hour))
	if e != nil || conflicts.(map[string]any)["total"] != 1 {
		t.Fatal(conflicts, e)
	}
}
func TestCalendarHTTPContracts(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, tc := range []struct {
		method, path, body string
		status             int
		steps              []sqlStep
	}{
		{"GET", eventPath + "?from=bad", "", 400, nil}, {"GET", eventPath + "?to=bad", "", 400, nil}, {"GET", eventPath + "?page=bad", "", 400, nil}, {"GET", eventPath + "?size=2147483648", "", 400, nil}, {"PUT", eventPath, "", 405, nil}, {"GET", eventPath + "/bad", "", 400, nil}, {"GET", eventPath + "/x/y", "", 404, nil}, {"POST", eventPath, "", 400, nil}, {"POST", eventPath, `{"title":{},"startsAt":"bad"}`, 400, nil}, {"POST", eventPath, `{"startsAt":"bad"}`, 400, nil}, {"POST", eventPath, `{"recurrenceType":"BAD"}`, 400, nil}, {"GET", eventPath + "/conflicts", "", 400, nil}, {"POST", eventPath + "/conflicts", "{}", 405, nil}, {"GET", eventPath + "/ics/preview", "", 405, nil}, {"POST", eventPath + "/ics/preview", `{"content":"bad"}`, 400, nil}, {"GET", eventPath + "/" + calendarID, "", 200, []sqlStep{queryStep("WHERE id=$1", calendarRow())}}, {"HEAD", eventPath + "/" + calendarID, "", 200, []sqlStep{queryStep("WHERE id=$1", calendarRow())}},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			s := calendarFixture(t, tc.steps...)
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			s.Handler(logger).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
