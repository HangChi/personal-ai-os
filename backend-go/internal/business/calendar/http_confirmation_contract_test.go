package calendar

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"strings"
	"testing"
)

type deleteConfirmationStore struct {
	confirmation.Store
	saved []confirmation.Record
}

func (s *deleteConfirmationStore) Create(_ context.Context, r confirmation.Record) (confirmation.Record, error) {
	s.saved = append(s.saved, r)
	return r, nil
}
func TestCalendarDeleteConfirmationContracts(t *testing.T) {
	ctx := context.Background()
	for _, ids := range [][]string{nil, make([]string, 51), {"bad"}} {
		if _, e := calendarFixture(t).RequestDelete(ctx, nil, ids, "c"); e == nil {
			t.Fatal(ids)
		}
	}
	for _, mode := range []string{"one", "two", "missing", "failure"} {
		t.Run(mode, func(t *testing.T) {
			ids := []string{calendarID, strings.ToUpper(calendarID)}
			steps := []sqlStep{queryStep("FROM calendar_event WHERE", calendarRow())}
			if mode == "two" {
				id2 := "20000000-0000-4000-8000-000000000001"
				r := calendarRow()
				r[0] = id2
				r[1] = "second"
				steps = append(steps, queryStep("FROM calendar_event WHERE", r))
				ids = append(ids, id2)
			}
			if mode == "missing" {
				steps[0].rows = nil
			}
			if mode == "failure" {
				steps[0].err = errors.New("read failed")
			}
			s := calendarFixture(t, steps...)
			store := &deleteConfirmationStore{}
			reg, e := confirmation.NewRegistryFor(s.Actions(), s)
			if e != nil {
				t.Fatal(e)
			}
			svc, e := confirmation.NewService(store, reg)
			if e != nil {
				t.Fatal(e)
			}
			dto, e := s.RequestDelete(ctx, svc, ids, "c")
			if mode == "missing" || mode == "failure" {
				if e == nil || len(store.saved) != 0 {
					t.Fatal(e, store.saved)
				}
				return
			}
			if e != nil || len(store.saved) != 1 || dto.ConversationID != "c" || dto.Action != confirmation.DeleteCalendarEvent {
				t.Fatal(dto, e)
			}
			var p deletePayload
			if json.Unmarshal([]byte(store.saved[0].Payload), &p) != nil || len(p.IDs) != dto.AffectedCount {
				t.Fatal(p, dto)
			}
			if mode == "two" && !strings.Contains(dto.Summary, "2") {
				t.Fatal(dto)
			}
		})
	}
	if _, e := calendarFixture(t).Execute(ctx, confirmation.DeleteTask, "{}"); e == nil {
		t.Fatal("action")
	}
	if _, e := calendarFixture(t).Execute(ctx, confirmation.DeleteCalendarEvent, "{"); e == nil {
		t.Fatal("json")
	}
	for _, mode := range []string{"delete", "missing", "error"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), queryStep("FOR UPDATE", sqlValues(calendarID))}
			if mode == "missing" {
				steps[1].rows = nil
				steps = append(steps, terminalStep("rollback"))
			} else if mode == "error" {
				steps[1].err = errors.New("lock failed")
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, execStep("DELETE FROM reminder"), execStep("DELETE FROM calendar_event"), terminalStep("commit"))
			}
			s := calendarFixture(t, steps...)
			raw, _ := json.Marshal(deletePayload{IDs: []string{calendarID, calendarID}})
			n, e := s.Execute(ctx, confirmation.DeleteCalendarEvent, string(raw))
			if mode == "delete" {
				if n != 1 || e != nil {
					t.Fatal(n, e)
				}
			} else if e == nil {
				t.Fatal(n, e)
			}
		})
	}
}
func TestReminderHTTPContracts(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, tc := range []struct {
		method, path string
		status       int
		query        bool
	}{
		{"GET", reminderPath, 200, true}, {"HEAD", reminderPath + "?status=READ&dueOnly=no&limit=2", 200, true}, {"GET", reminderPath + "?dueOnly=yes", 200, true}, {"GET", reminderPath + "?status=bad", 400, false}, {"GET", reminderPath + "?dueOnly=bad", 400, false}, {"GET", reminderPath + "?limit=overflow", 400, false}, {"POST", reminderPath, 405, false}, {"GET", reminderPath + "/scan", 405, false}, {"GET", reminderPath + "/unknown", 404, false}, {"GET", reminderPath + "/id/read", 405, false}, {"POST", reminderPath + "/bad/read", 400, false}, {"POST", reminderPath + "/" + calendarID + "/read", 200, true},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			steps := []sqlStep{}
			if tc.query {
				q := "SELECT"
				if tc.method == "POST" {
					q = "UPDATE reminder"
				}
				steps = append(steps, queryStep(q, reminderRow()))
			}
			s := calendarFixture(t, steps...)
			w := httptest.NewRecorder()
			s.Handler(logger).ServeHTTP(w, httptest.NewRequest(tc.method, tc.path, nil))
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
			if tc.method == "HEAD" && w.Body.Len() != 0 {
				t.Fatal("HEAD body")
			}
		})
	}
}
func TestPushHTTPContracts(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, enabled := range []bool{false, true} {
		for _, method := range []string{"GET", "HEAD", "POST"} {
			p := &Push{enabled: enabled, public: "key"}
			w := httptest.NewRecorder()
			p.Handler(logger).ServeHTTP(w, httptest.NewRequest(method, pushPath, nil))
			want := 200
			if method == "POST" {
				want = 405
			}
			if w.Code != want {
				t.Fatal(w.Code, w.Body.String())
			}
			if method == "GET" && !strings.Contains(w.Body.String(), "enabled") {
				t.Fatal(w.Body.String())
			}
		}
	}
	for _, v := range []any{nil, []any{}, map[string]any{}, map[string]any{"endpoint": strings.Repeat("x", 2049)}, map[string]any{"endpoint": "https://push.test", "keys": "bad"}, map[string]any{"endpoint": "https://push.test", "keys": map[string]any{"p256dh": "key"}}} {
		b, _ := json.Marshal(v)
		w := httptest.NewRecorder()
		p := &Push{}
		r := httptest.NewRequest("POST", pushPath+"/subscriptions", strings.NewReader(string(b)))
		r.Header.Set("Content-Type", "application/json")
		p.Handler(logger).ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	p, key, auth := pushFixture(t, terminalStep("begin"), execStep("SELECT pg_advisory_xact_lock"), queryStep("SELECT EXISTS", sqlValues(true)), queryStep("INSERT INTO web_push_subscription", sqlValues(calendarID)), terminalStep("commit"), execStep("DELETE FROM web_push_subscription"))
	b, _ := json.Marshal(map[string]any{"endpoint": "https://fcm.googleapis.com/send", "keys": map[string]any{"p256dh": key, "auth": auth}})
	for _, method := range []string{"POST", "DELETE"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest(method, pushPath+"/subscriptions", strings.NewReader(string(b)))
		r.Header.Set("Content-Type", "application/json")
		p.Handler(logger).ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", pushPath, nil)
	r.Header.Set("Accept", "image/png")
	p.Handler(logger).ServeHTTP(w, r)
	if w.Code != 406 {
		t.Fatal(w.Code)
	}
	if !OwnsPushPath(pushPath) || !OwnsPushPath(pushPath+"/subscriptions") || OwnsPushPath(pushPath+"/bad") {
		t.Fatal("ownership")
	}
}
