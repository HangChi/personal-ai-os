package calendar

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCalendarHTTPRejectsMalformedFieldsAndUnsupportedRoutes(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"PUT", "", "", 405}, {"GET", "?from=invalid", "", 400}, {"GET", "?to=invalid", "", 400}, {"GET", "?page=bad", "", 400}, {"GET", "?size=bad", "", 400},
		{"POST", "", "", 400}, {"POST", "", "{", 400}, {"PATCH", "/" + calendarID, "{", 400}, {"POST", "/" + calendarID, "{}", 405}, {"GET", "/bad", "", 400}, {"GET", "/" + calendarID + "/bad", "", 404},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			r := httptest.NewRequest(tc.method, eventPath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			calendarFixture(t).Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	for _, field := range []string{"title", "description", "location", "startsAt", "endsAt", "recurrenceType", "recurrenceUntil", "allDay", "clearRecurrenceUntil", "reminderLeadMinutes"} {
		t.Run(field, func(t *testing.T) {
			b, _ := json.Marshal(map[string]any{field: map[string]any{}})
			r := httptest.NewRequest("PATCH", eventPath+"/"+calendarID, strings.NewReader(string(b)))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			calendarFixture(t).Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != 400 {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}

func TestCalendarExchangeHTTPValidationAndExport(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"GET", "/conflicts?from=2026-11-07T00:00:00Z&to=bad", "", 400},
		{"GET", "/conflicts?from=2026-11-07T00:00:00Z&to=2026-11-08T00:00:00Z", "", 200},
		{"GET", "/conflicts?from=2026-11-07T00:00:00Z&to=2026-11-06T00:00:00Z", "", 400},
		{"POST", "/ics/preview", "{", 400}, {"POST", "/ics/preview", `{"content":42}`, 400},
		{"POST", "/ics/import", `{"content":"invalid","digest":"bad"}`, 400},
		{"POST", "/ics", "", 405}, {"GET", "/ics", "", 200}, {"HEAD", "/ics", "", 200},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			r := httptest.NewRequest(tc.method, eventPath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			steps := []sqlStep{}
			if tc.status == 200 {
				steps = append(steps, queryStep("FROM calendar_event ORDER BY"))
				if strings.HasPrefix(tc.path, "/ics") {
					steps = append(steps, queryStep("FROM calendar_ics_identity"))
				}
			}
			calendarFixture(t, steps...).Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
			if tc.method == "HEAD" && w.Body.Len() != 0 {
				t.Fatal("HEAD has body")
			}
		})
	}
	svc := calendarFixture(t)
	if _, e := svc.conflicts(context.Background(), time.Now(), time.Now().Add(32*24*time.Hour)); e == nil {
		t.Fatal("unbounded conflict range")
	}
}
