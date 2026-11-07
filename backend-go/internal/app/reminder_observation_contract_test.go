package app

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/health"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type brokenReminderWriter struct{ *httptest.ResponseRecorder }

func (brokenReminderWriter) Write([]byte) (int, error) { return 0, errors.New("client disconnected") }

func TestReminderObservationFailureTagsAndZeroGauge(t *testing.T) {
	var reads atomic.Int32
	script := &sqlScript{t: t, responder: func(op, q string, a []driver.NamedValue) (sqlStep, error) {
		if op != "query" || !strings.Contains(q, "FROM reminder") {
			return sqlStep{}, errors.New("unexpected operation")
		}
		if reads.Add(1) == 1 {
			return sqlStep{}, errors.New("read unavailable")
		}
		return sqlStep{}, nil
	}}
	pool, e := database.Open(context.Background(), sqlConnector{script}, database.DefaultPoolOptions(false))
	if e != nil {
		t.Fatal(e)
	}
	defer pool.Close()
	stream := calendar.NewStream(context.Background(), calendar.NewEvents(pool, config.CalendarProperties{DefaultQueryLimit: 50, MaxQueryLimit: 500}, time.UTC), time.Millisecond)
	defer stream.Close()
	m := &foundationMetrics{p5: &foundationP5{stream: stream, broadcast: &calendar.Broadcast{}}}
	done := make(chan error, 1)
	r := httptest.NewRequest("GET", "/api/v1/reminders/events", nil)
	r.Header.Set("Accept", "text/event-stream")
	go func() { done <- stream.Serve(brokenReminderWriter{httptest.NewRecorder()}, r) }()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("stream failed to stop after write failure")
	}
	stats := stream.Stats()
	if stats.ReadFailures != 1 || stats.WriteFailures != 1 || stats.Connections != 0 {
		t.Fatal(stats)
	}
	for _, tc := range []struct {
		name       string
		tags       []string
		value      float64
		stat       string
		found, bad bool
	}{
		{"personalos.reminders.stream.connections", nil, 0, "VALUE", true, false},
		{"personalos.reminders.stream.failures", nil, 2, "COUNT", true, false},
		{"personalos.reminders.stream.failures", []string{"stage:read"}, 1, "COUNT", true, false},
		{"personalos.reminders.stream.failures", []string{"stage:write"}, 1, "COUNT", true, false},
		{"personalos.reminders.stream.failures", []string{"stage:missing"}, 0, "", false, false},
		{"personalos.reminders.stream.connections", []string{"stage:read"}, 0, "", false, false},
		{"personalos.reminders.stream.failures", []string{"bad"}, 0, "", false, true},
		{"unknown", nil, 0, "", false, false},
	} {
		v, ok, err := m.reminderMetric(tc.name, url.Values{"tag": tc.tags})
		if ok != tc.found || (err != nil) != tc.bad {
			t.Fatal(tc, v, err)
		}
		if ok {
			metric := v.(actuatorMetric)
			if metric.Measurements[0].Value != tc.value || metric.Measurements[0].Statistic != tc.stat {
				t.Fatal(metric)
			}
			if len(tc.tags) == 0 && strings.HasSuffix(tc.name, "failures") && (len(metric.AvailableTags) != 1 || len(metric.AvailableTags[0].Values) != 2) {
				t.Fatal(metric)
			}
		}
	}
	w := httptest.NewRecorder()
	m.writeReminderMetrics(w)
	b := w.Body.String()
	if strings.Count(b, "# TYPE personalos_reminders_stream_failures_total counter") != 1 || !strings.Contains(b, "personalos_reminders_stream_connections 0") {
		t.Fatal(b)
	}
	if (&foundationMetrics{}).reminderMetrics() != nil {
		t.Fatal("nil foundation emitted series")
	}
}

func TestActuatorSnapshotReflectsDatabaseAndDiskWithoutModelCalls(t *testing.T) {
	for _, up := range []bool{true, false} {
		h, e := health.New(health.Options{ProbeTimeout: time.Second}, func(context.Context) error {
			if !up {
				return errors.New("private database URL")
			}
			return nil
		}, func() bool { return false }, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if e != nil {
			t.Fatal(e)
		}
		f := &Foundation{health: h}
		state := f.actuatorSnapshot(config.ActuatorProperties{DiskSpace: true}, []*redisclient.Client{nil, nil})(context.Background())
		want := "DOWN"
		if up {
			want = "UP"
		}
		if state.Components["db"].Status != want || state.Status != want {
			t.Fatal(state)
		}
		if up && state.Components["db"].Details["validationQuery"] != "SELECT 1" {
			t.Fatal(state)
		}
		if strings.Contains(state.Status, "private") {
			t.Fatal(state)
		}
	}
}
