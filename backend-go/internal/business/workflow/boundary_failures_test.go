package workflow

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestWorkflowReadWriteFailureBoundaries(t *testing.T) {
	ctx := context.Background()
	name, local, zone := "name", "09:00", "UTC"
	cases := []struct {
		name  string
		steps []sqlStep
		call  func(*Service) error
	}{
		{"list", []sqlStep{queryStep("SELECT count", sqlValues(1)), queryStep("ORDER BY created_at", defRow())}, func(s *Service) error { _, e := s.List(ctx); return e }},
		{"create", []sqlStep{terminalStep("begin"), execStep("pg_advisory"), queryStep("count(*)", sqlValues(0)), queryStep("pg_timezone", sqlValues(true)), queryStep("INSERT INTO workflow", defRow()), terminalStep("commit")}, func(s *Service) error {
			_, e := s.Save(ctx, "", Change{Name: &name, LocalTime: &local, Timezone: &zone})
			return e
		}},
		{"update", []sqlStep{terminalStep("begin"), execStep("pg_advisory"), queryStep("WHERE id=$1", defRow()), queryStep("pg_timezone", sqlValues(true)), queryStep("UPDATE workflow", defRow()), terminalStep("commit")}, func(s *Service) error { _, e := s.Save(ctx, "w", Change{}); return e }},
		{"run", append([]sqlStep{queryStep("FROM workflow WHERE", defRow()), queryStep("enqueue_workflow", sqlValues("e"))}, executionSteps()...), func(s *Service) error { _, e := s.Run(ctx, "w"); return e }},
		{"retry", append([]sqlStep{terminalStep("begin"), queryStep("status,attempts", sqlValues("FAILED", 2)), execStep("status='PENDING'"), terminalStep("commit")}, executionSteps()...), func(s *Service) error { _, e := s.Retry(ctx, "e"); return e }},
		{"history", []sqlStep{queryStep("FROM workflow WHERE", defRow()), queryStep("count(*)", sqlValues(1)), queryStep("WHERE e.workflow_id", executionRow()), executionSteps()[1]}, func(s *Service) error { _, e := s.History(ctx, "w"); return e }},
		{"report", []sqlStep{queryStep("FROM knowledge_document", sqlValues("title", time.Now())), queryStep("FROM knowledge_capture", sqlValues("url", "SUCCEEDED"))}, func(s *Service) error {
			_, e := s.Report(ctx, Snapshot{Kind: "KNOWLEDGE_REVIEW", Timezone: "UTC", MaxItems: 2, MaxOutputChars: 1000}, time.Now())
			return e
		}},
	}
	for _, tc := range cases {
		for i, target := range tc.steps {
			for _, fault := range []string{"statement", "rows", "scan"} {
				if target.op != "query" && fault != "statement" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", tc.name, i, fault), func(t *testing.T) {
					steps := append([]sqlStep{}, tc.steps[:i+1]...)
					failure := errors.New("storage failure")
					switch fault {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					inTx := false
					for _, p := range tc.steps[:i] {
						if p.op == "begin" {
							inTx = true
						}
						if p.op == "commit" {
							inTx = false
						}
					}
					if inTx && target.op != "commit" {
						steps = append(steps, terminalStep("rollback"))
					}
					if e := tc.call(&Service{Pool: contractPool(t, steps...)}); e == nil {
						t.Fatal("failure swallowed")
					}
				})
			}
		}
	}
	for _, fn := range []func(*Service) error{func(s *Service) error { _, e := s.Get(ctx, "missing"); return e }, func(s *Service) error { _, e := s.Execution(ctx, "missing"); return e }} {
		if e := fn(&Service{Pool: contractPool(t, queryStep("WHERE"))}); e == nil {
			t.Fatal("missing accepted")
		}
	}
}
func TestWorkflowHTTPBoundaries(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"PUT", "", "", 405}, {"POST", "", "{", 400}, {"POST", "", `{"name":{}}`, 400},
		{"GET", "/" + id, "", 405}, {"PATCH", "/" + id, "{", 400}, {"PATCH", "/" + id, `{"maxItems":{}}`, 400},
		{"GET", "/runs/" + id + "/retry", "", 405}, {"POST", "/runs/bad/retry", "", 400},
		{"POST", "/" + id + "/runs", "", 405}, {"GET", "/" + id + "/run", "", 405}, {"GET", "/" + id + "/unknown", "", 404},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			s := &Service{Pool: contractPool(t)}
			r := httptest.NewRequest(tc.method, basePath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			s.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
