package task

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"strings"
	"testing"
	"time"
)

type httpTaskStore struct {
	acceptStore
	fail error
	q    Query
}

func (s *httpTaskStore) Insert(_ context.Context, v Task) (Task, error) {
	s.writes++
	v.ID = bindingTaskID
	s.task = v
	return v, s.fail
}
func (s *httpTaskStore) Get(context.Context, string) (Task, bool, error) { return s.task, true, s.fail }
func (s *httpTaskStore) Update(_ context.Context, v Task) (Task, error) {
	s.writes++
	s.task = v
	return v, s.fail
}
func (s *httpTaskStore) Delete(context.Context, string) (bool, error) {
	s.writes++
	return true, s.fail
}
func (s *httpTaskStore) Search(_ context.Context, q Query) (Page, error) {
	s.q = q
	return Page{Items: []Task{s.task}, Page: q.Page, Size: q.Size, Total: 1}, s.fail
}
func (s *httpTaskStore) Stats(context.Context, time.Time) (Stats, error) {
	return Stats{Pending: 1}, s.fail
}
func (s *httpTaskStore) ListTags(context.Context) ([]string, error) {
	return []string{"标签"}, s.fail
}
func (s *httpTaskStore) ExportBatch(context.Context, int, int) ([]Task, error) {
	return []Task{s.task}, s.fail
}
func (s *httpTaskStore) SearchHits(context.Context, string, int) ([]Task, error) {
	return []Task{s.task}, s.fail
}
func (s *httpTaskStore) Projects(context.Context) ([]Project, error) {
	return []Project{{ID: bindingTaskID, Name: "项目"}}, s.fail
}
func (s *httpTaskStore) SaveProject(_ context.Context, id string, c ProjectChange) (Project, error) {
	s.writes++
	return Project{ID: bindingTaskID, Name: *c.Name}, s.fail
}
func (s *httpTaskStore) UnfinishedRuns(context.Context) (RunPage, error) {
	return RunPage{Items: []RunSummary{}, Total: 0}, s.fail
}
func taskHTTPContract(t *testing.T) (*HTTP, *httpTaskStore) {
	t.Helper()
	s := &httpTaskStore{acceptStore: acceptStore{task: Task{ID: bindingTaskID, Title: "标题", Status: Todo, Priority: Medium, RecurrenceType: "NONE", RecurrenceTimezone: "UTC"}}}
	svc, _ := NewService(s)
	return &HTTP{Tasks: svc, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, s
}
func taskRequest(t *testing.T, h *HTTP, method, path string, body any, status int) {
	t.Helper()
	text := ""
	if body != nil {
		b, _ := json.Marshal(body)
		text = string(b)
	}
	r := httptest.NewRequest(method, path, strings.NewReader(text))
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != status {
		t.Fatalf("%s %s: %d %s", method, path, w.Code, w.Body.String())
	}
	if method == "HEAD" && w.Body.Len() != 0 {
		t.Fatal("HEAD body")
	}
}
func TestTaskHTTPBusinessAndWorkspaceContract(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "failure"}[fail], func(t *testing.T) {
			h, s := taskHTTPContract(t)
			if fail {
				s.fail = errors.New("store offline")
			}
			status := 200
			if fail {
				status = 500
			}
			for _, p := range []string{"/api/v1/tasks", "/api/v1/tasks/tags", "/api/v1/tasks/stats", "/api/v1/tasks/" + bindingTaskID, "/api/v1/projects", "/api/v1/workspace/runs"} {
				taskRequest(t, h, "GET", p, nil, status)
			}
			for _, action := range []string{"complete", "reopen"} {
				taskRequest(t, h, "POST", "/api/v1/tasks/"+bindingTaskID+"/"+action, nil, status)
			}
			taskRequest(t, h, "PATCH", "/api/v1/tasks/"+bindingTaskID, map[string]any{"title": "修改", "clearDueAt": true, "clearParent": true, "clearProject": true, "tags": []string{"标签"}}, status)
			taskRequest(t, h, "DELETE", "/api/v1/tasks/"+bindingTaskID, nil, status)
			created := 201
			if fail {
				created = 500
			}
			taskRequest(t, h, "POST", "/api/v1/tasks", map[string]any{"title": "新任务", "ignored": true}, created)
			taskRequest(t, h, "POST", "/api/v1/projects", map[string]any{"name": "新项目"}, created)
			taskRequest(t, h, "PATCH", "/api/v1/projects/"+bindingTaskID, map[string]any{"name": "改名"}, status)
		})
	}
	h, s := taskHTTPContract(t)
	for _, p := range []string{"/api/v1/tasks", "/api/v1/tasks/tags", "/api/v1/tasks/stats", "/api/v1/tasks/" + bindingTaskID, "/api/v1/projects", "/api/v1/workspace/runs"} {
		taskRequest(t, h, "HEAD", p, nil, 200)
	}
	q := url.Values{"status": {"DOING"}, "keyword": {"标题"}, "tag": {"标签"}, "page": {"2"}, "size": {"5"}, "incomplete": {"true"}, "dueFrom": {"2026-11-07T00:00:00Z"}, "dueBefore": {"2026-11-08T00:00:00Z"}, "projectId": {bindingTaskID}, "parentTaskId": {bindingTaskID}}
	taskRequest(t, h, "GET", "/api/v1/tasks?"+q.Encode(), nil, 200)
	if s.q.Page != 2 || s.q.Size != 5 || !s.q.Incomplete || s.q.DueFrom == nil || s.q.Status == nil {
		t.Fatal(s.q)
	}
	for _, field := range []string{"page", "size", "status", "projectId", "parentTaskId", "incomplete", "dueFrom", "dueBefore"} {
		taskRequest(t, h, "GET", "/api/v1/tasks?"+field+"=invalid", nil, 400)
	}
	for _, p := range []string{"/api/v1/tasks", "/api/v1/tasks/tags", "/api/v1/tasks/stats", "/api/v1/tasks/" + bindingTaskID, "/api/v1/tasks/" + bindingTaskID + "/complete", "/api/v1/projects", "/api/v1/projects/" + bindingTaskID, "/api/v1/workspace/runs", "/api/v1/agent/confirmations", "/api/v1/agent/confirmations/" + bindingTaskID + "/confirm"} {
		taskRequest(t, h, "PUT", p, nil, 405)
	}
	for _, p := range []string{"/unknown", "/api/v1/tasks/bad", "/api/v1/tasks/" + bindingTaskID + "/extra/x", "/api/v1/projects/bad", "/api/v1/agent/confirmations/bad/x"} {
		taskRequest(t, h, "POST", p, nil, 404)
	}
	taskRequest(t, h, "POST", "/api/v1/tasks/"+bindingTaskID+"/unknown", nil, 404)
	taskRequest(t, h, "GET", "/api/v1/agent/confirmations?status=bad", nil, 400)
	for _, v := range []any{map[string]any{"title": strings.Repeat("x", 201)}, map[string]any{"description": strings.Repeat("x", 2001)}, map[string]any{"title": map[string]int{}}, map[string]any{"priority": []string{}}, map[string]any{"title": nil}} {
		taskRequest(t, h, "POST", "/api/v1/tasks", v, 400)
	}
	for _, v := range []any{map[string]any{}, map[string]any{"token": map[string]int{}}} {
		taskRequest(t, h, "POST", "/api/v1/agent/confirmations/"+bindingTaskID+"/confirm", v, 400)
	}
	for _, v := range []any{map[string]any{}, map[string]any{"name": strings.Repeat("x", 101)}, map[string]any{"name": "x", "description": strings.Repeat("x", 2001)}, map[string]any{"name": []string{}}} {
		taskRequest(t, h, "POST", "/api/v1/projects", v, 400)
	}
	if _, e := NewHTTP(nil, nil, nil, nil); e == nil {
		t.Fatal("missing dependencies")
	}
	if _, e := NewHTTP(h.Tasks, new(idempotency.Service), new(confirmation.Service), h.logger); e != nil {
		t.Fatal(e)
	}
}
func TestTaskContributorsAndUpdateValidationContract(t *testing.T) {
	h, s := taskHTTPContract(t)
	ctx := context.Background()
	e := ExportContributor{h.Tasks}
	if e.Key() != "tasks" {
		t.Fatal(e.Key())
	}
	if v, err := e.Batch(ctx, 0, 5); err != nil || len(v) != 1 {
		t.Fatal(v, err)
	}
	c := SearchContributor{h.Tasks}
	if c.Kind() != "task" || c.ModuleName() != "任务" {
		t.Fatal(c)
	}
	for _, status := range []Status{Todo, Doing, Done} {
		for _, priority := range []Priority{Low, Medium, High} {
			s.task.Status = status
			s.task.Priority = priority
			for _, word := range []string{"标题", "not title"} {
				v, e := c.Search(ctx, word, 5)
				if e != nil || len(v) != 1 || v[0].MatchedField == "" || v[0].Subtitle == "" {
					t.Fatal(v, e)
				}
			}
		}
	}
	s.fail = errors.New("offline")
	if _, err := e.Batch(ctx, 0, 5); err == nil {
		t.Fatal("export failure")
	}
	if _, err := c.Search(ctx, "x", 5); err == nil {
		t.Fatal("search failure")
	}
	s.fail = nil
	bad := "bad"
	priority := Priority("bad")
	status := Status("bad")
	tags := []string{strings.Repeat("x", 1000)}
	for _, change := range []UpdateRequest{{Title: &bad, RecurrenceType: &bad}, {Priority: &priority}, {Status: &status}, {Tags: &tags}} {
		before := s.writes
		if _, err := h.Tasks.Update(ctx, bindingTaskID, change); err == nil || s.writes != before {
			t.Fatal("invalid mutation reached storage", change)
		}
	}
}
