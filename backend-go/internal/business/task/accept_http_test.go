package task

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
)

type acceptStore struct {
	Store
	task   Task
	writes int
}

func (s *acceptStore) Get(context.Context, string) (Task, bool, error) { return s.task, true, nil }
func (s *acceptStore) Update(_ context.Context, v Task) (Task, error) {
	s.task = v
	s.writes++
	return v, nil
}

func TestTaskAcceptBeforeCompletion(t *testing.T) {
	for _, action := range []string{"complete", "reopen"} {
		t.Run(action, func(t *testing.T) {
			store := &acceptStore{task: Task{ID: bindingTaskID, Title: "fixture", Status: Todo, Priority: Medium}}
			service, err := NewService(store)
			if err != nil {
				t.Fatal(err)
			}
			h := &HTTP{Tasks: service, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
			r := httptest.NewRequest("POST", "/api/v1/tasks/"+bindingTaskID+"/"+action, nil)
			r.Header.Set("Accept", "text/plain")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 406 || store.writes != 0 {
				t.Fatalf("status=%d writes=%d", w.Code, store.writes)
			}
		})
	}
}

func TestWriteAcceptBeforeServiceOrIdempotency(t *testing.T) {
	// 空依赖作为哨兵：拒绝请求不得进入仓储、幂等记录或确认执行器。
	h, err := NewHTTP(new(Service), new(idempotency.Service), new(confirmation.Service), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ method, path, body string }{
		{"POST", "/api/v1/tasks", `{"title":"fixture"}`},
		{"PATCH", "/api/v1/tasks/" + bindingTaskID, `{"title":"fixture"}`},
		{"DELETE", "/api/v1/tasks/" + bindingTaskID, ``},
		{"POST", "/api/v1/projects", `{"name":"fixture"}`},
		{"PATCH", "/api/v1/projects/" + bindingTaskID, `{"name":"fixture"}`},
		{"POST", "/api/v1/agent/confirmations/" + bindingTaskID + "/confirm", `{"token":"ok"}`},
		{"POST", "/api/v1/agent/confirmations/" + bindingTaskID + "/cancel", `{"token":"ok"}`},
	} {
		for _, key := range []string{"", "rejected-request-001"} {
			t.Run(tc.method+tc.path+key, func(t *testing.T) {
				r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
				r.Header.Set("Content-Type", "application/json")
				r.Header.Set("Accept", "text/plain")
				r.Header.Set("Idempotency-Key", key)
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != 406 {
					t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
				}
			})
		}
	}
}
