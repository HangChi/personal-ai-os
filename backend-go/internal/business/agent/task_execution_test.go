package agent

import (
	"context"
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/api"
	"reflect"
	"strings"
	"testing"
	"time"
)

// 使用业务仓储接口记录真实写入；未实现的方法若被调用会立即失败。
type toolTaskStore struct {
	task.Store
	items  map[string]task.Task
	writes int
	fail   error
	query  task.Query
}

func (s *toolTaskStore) Insert(_ context.Context, v task.Task) (task.Task, error) {
	if s.fail != nil {
		return task.Task{}, s.fail
	}
	v.ID = UUID()
	s.items[v.ID] = v
	s.writes++
	return v, nil
}
func (s *toolTaskStore) Get(_ context.Context, id string) (task.Task, bool, error) {
	v, ok := s.items[id]
	return v, ok, s.fail
}
func (s *toolTaskStore) Update(_ context.Context, v task.Task) (task.Task, error) {
	if s.fail != nil {
		return task.Task{}, s.fail
	}
	s.items[v.ID] = v
	s.writes++
	return v, nil
}
func (s *toolTaskStore) Search(_ context.Context, q task.Query) (task.Page, error) {
	s.query = q
	items := []task.Task{}
	for _, v := range s.items {
		if q.Keyword == "" || strings.Contains(v.Title, q.Keyword) {
			items = append(items, v)
		}
	}
	return task.Page{Items: items, Total: int64(len(items))}, s.fail
}
func (s *toolTaskStore) FindByIDs(_ context.Context, ids []string) ([]task.Task, error) {
	found := []task.Task{}
	for _, id := range ids {
		if v, ok := s.items[id]; ok {
			found = append(found, v)
		}
	}
	return found, s.fail
}
func (s *toolTaskStore) DeleteAll(_ context.Context, ids []string) (int, error) {
	if s.fail != nil {
		return 0, s.fail
	}
	n := 0
	for _, id := range ids {
		if _, ok := s.items[id]; ok {
			delete(s.items, id)
			n++
			s.writes++
		}
	}
	return n, nil
}

type toolConfirmationStore struct {
	confirmation.Store
	records map[string]confirmation.Record
}

func (s *toolConfirmationStore) Create(_ context.Context, r confirmation.Record) (confirmation.Record, error) {
	r.ID = UUID()
	s.records[r.ID] = r
	return r, nil
}
func (s *toolConfirmationStore) Get(_ context.Context, id string) (confirmation.Record, bool, error) {
	r, ok := s.records[id]
	return r, ok, nil
}
func (s *toolConfirmationStore) CompareAndSet(_ context.Context, id string, from, to confirmation.Status, now time.Time) (bool, error) {
	r, ok := s.records[id]
	if !ok || r.Status != from {
		return false, nil
	}
	r.Status = to
	r.ResolvedAt = &now
	s.records[id] = r
	return true, nil
}
func taskToolFixture(t *testing.T) (map[string]Definition, *toolTaskStore, *toolConfirmationStore, *confirmation.Service) {
	t.Helper()
	store := &toolTaskStore{items: map[string]task.Task{}}
	service, e := task.NewService(store)
	if e != nil {
		t.Fatal(e)
	}
	handler, e := task.NewConfirmationHandler(service)
	if e != nil {
		t.Fatal(e)
	}
	registry, e := confirmation.NewRegistryFor(handler.Actions(), handler)
	if e != nil {
		t.Fatal(e)
	}
	approvals := &toolConfirmationStore{records: map[string]confirmation.Record{}}
	confirms, e := confirmation.NewService(approvals, registry)
	if e != nil {
		t.Fatal(e)
	}
	requests, e := task.NewConfirmationRequests(service, confirms)
	if e != nil {
		t.Fatal(e)
	}
	definitions := map[string]Definition{}
	for _, d := range TaskTools(service, requests) {
		definitions[d.Model.Name] = d
	}
	return definitions, store, approvals, confirms
}
func executeTaskTool(t *testing.T, defs map[string]Definition, name string, args map[string]any) Result {
	t.Helper()
	d := defs[name]
	normalized, e := d.Normalize(args)
	if e != nil {
		t.Fatal(name, e)
	}
	r, e := d.Action(context.Background(), testTurn(), normalized)
	if e != nil {
		t.Fatal(name, e)
	}
	return r
}
func TestTaskToolsExecuteCRUDAndHumanConfirmedMutations(t *testing.T) {
	defs, store, approvals, confirms := taskToolFixture(t)
	ctx := context.Background()
	created := executeTaskTool(t, defs, "createTask", map[string]any{"title": "  核对报告  ", "priority": " high ", "description": "检查来源", "tags": []any{"Agent"}, "dueAt": "2026-11-08"})
	dto := created.Data.(task.DTO)
	id := dto.ID
	if created.Status != "OK" || dto.Title != "核对报告" || dto.Priority != task.High || dto.DueAt == nil || len(dto.Tags) != 1 {
		t.Fatal(created)
	}
	executeTaskTool(t, defs, "createTask", map[string]any{"title": "另一个任务"})
	result := executeTaskTool(t, defs, "findTasks", map[string]any{"keyword": "核对", "status": "todo", "limit": json.Number("1000"), "tag": "Agent"})
	if len(result.Data.([]task.DTO)) != 1 || store.query.Size != 100 || store.query.Status == nil || *store.query.Status != task.Todo {
		t.Fatal(result, store.query)
	}
	empty := executeTaskTool(t, defs, "findTasks", map[string]any{"keyword": "不存在"})
	if empty.Message != "没有符合条件的任务" {
		t.Fatal(empty)
	}
	got := executeTaskTool(t, defs, "getTask", map[string]any{"taskId": id})
	if got.Data.(task.DTO).ID != id {
		t.Fatal(got)
	}
	executeTaskTool(t, defs, "updateTaskDetails", map[string]any{"taskId": id, "title": "新标题", "description": "", "tags": []any{}})
	if store.items[id].Title != "新标题" || len(store.items[id].Tags) != 0 || store.items[id].Description != nil {
		t.Fatal(store.items[id])
	}
	for _, completed := range []bool{true, false} {
		r := executeTaskTool(t, defs, "setTaskCompleted", map[string]any{"taskId": id, "completed": completed})
		if (r.Data.(task.DTO).Status == task.Done) != completed {
			t.Fatal(r)
		}
	}
	for _, change := range []struct {
		name string
		args map[string]any
	}{
		{"scheduleTask", map[string]any{"taskId": id, "dueAt": "2026-12-01T09:00:00Z"}},
		{"scheduleTask", map[string]any{"taskId": id}},
		{"setTaskPriority", map[string]any{"taskId": id, "priority": "low"}},
		{"deleteTask", map[string]any{"taskIds": []any{id}}},
	} {
		before := store.writes
		r := executeTaskTool(t, defs, change.name, change.args)
		if r.Status != "REQUIRES_CONFIRMATION" || r.ConfirmationID == nil || store.writes != before {
			t.Fatal("mutation happened before human confirmation", r)
		}
		record := approvals.records[*r.ConfirmationID]
		if record.ConversationID != "c" {
			t.Fatal(record)
		}
		// 错误令牌不能执行，正确令牌只执行一次。
		if _, e := confirms.Confirm(ctx, record.ID, "wrong"); e == nil || store.writes != before {
			t.Fatal(e)
		}
		execution, e := confirms.Confirm(ctx, record.ID, record.Token)
		if e != nil || !execution.Executed || execution.AffectedCount != 1 || store.writes != before+1 {
			t.Fatal(execution, e)
		}
		if _, e = confirms.Confirm(ctx, record.ID, record.Token); e == nil || store.writes != before+1 {
			t.Fatal("confirmation replay mutated task")
		}
		if change.name == "scheduleTask" && change.args["dueAt"] == nil && store.items[id].DueAt != nil {
			t.Fatal("deadline was not cleared")
		}
		if change.name == "setTaskPriority" && store.items[id].Priority != task.Low {
			t.Fatal("priority was not applied")
		}
	}
	if _, ok := store.items[id]; ok {
		t.Fatal("confirmed deletion did not delete")
	}
}
func TestTaskToolNormalizationRejectsWrongTypesForEveryField(t *testing.T) {
	defs, _, _, _ := taskToolFixture(t)
	for name, d := range defs {
		t.Run(name, func(t *testing.T) {
			for _, key := range d.Keys {
				for _, invalid := range []any{map[string]any{}, []any{1}, true} {
					if key == "completed" && invalid == true {
						continue
					}
					// 只对匹配字段类型的值跳过，错误类型必须拒绝。
					if _, e := d.Normalize(map[string]any{key: invalid}); e == nil {
						t.Fatalf("%s accepted %s=%v", name, key, invalid)
					}
				}
			}
			raw := map[string]any{"unknown": "ignored"}
			normalized, e := d.Normalize(raw)
			if e != nil || len(normalized) != len(d.Keys) || !reflect.DeepEqual(raw, map[string]any{"unknown": "ignored"}) {
				t.Fatal(normalized, e)
			}
		})
	}
}
func TestTaskToolsRejectInvalidActionsWithoutWriting(t *testing.T) {
	defs, store, approvals, _ := taskToolFixture(t)
	id := UUID()
	cases := []struct {
		name string
		args map[string]any
	}{
		{"createTask", map[string]any{"title": "x", "priority": "URGENT"}},
		{"createTask", map[string]any{"title": "x", "dueAt": "tomorrow"}},
		{"createTask", map[string]any{"title": " "}},
		{"findTasks", map[string]any{"status": "UNKNOWN"}},
		{"getTask", map[string]any{"taskId": "bad"}},
		{"getTask", map[string]any{"taskId": id}},
		{"updateTaskDetails", map[string]any{"taskId": id}},
		{"setTaskCompleted", map[string]any{"taskId": id}},
		{"setTaskPriority", map[string]any{"taskId": id}},
		{"setTaskPriority", map[string]any{"taskId": id, "priority": "UNKNOWN"}},
		{"deleteTask", map[string]any{}},
		{"deleteTask", map[string]any{"taskIds": []any{"bad"}}},
		{"deleteTask", map[string]any{"taskIds": []any{id}}},
		{"scheduleTask", map[string]any{"taskId": id, "dueAt": "bad"}},
	}
	large := make([]any, 51)
	for i := range large {
		large[i] = id
	}
	cases = append(cases, struct {
		name string
		args map[string]any
	}{"deleteTask", map[string]any{"taskIds": large}})
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := defs[c.name]
			args, e := d.Normalize(c.args)
			if e != nil {
				t.Fatal(e)
			}
			_, e = d.Action(context.Background(), testTurn(), args)
			var business *api.BusinessError
			if !errors.As(e, &business) {
				t.Fatalf("expected business rejection, got %v", e)
			}
		})
	}
	if store.writes != 0 || len(approvals.records) != 0 {
		t.Fatal("rejected action had side effects")
	}
}
func TestTaskToolsPropagateStoreFailuresWithoutCreatingApprovals(t *testing.T) {
	defs, store, approvals, _ := taskToolFixture(t)
	store.fail = errors.New("synthetic storage failure")
	for _, name := range []string{"createTask", "getTask", "findTasks", "updateTaskDetails", "setTaskCompleted", "scheduleTask", "setTaskPriority", "deleteTask"} {
		args := map[string]any{"taskId": UUID(), "taskIds": []any{UUID()}, "title": "x", "completed": true, "priority": "HIGH"}
		d := defs[name]
		n, e := d.Normalize(args)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = d.Action(context.Background(), testTurn(), n); !errors.Is(e, store.fail) {
			t.Fatalf("%s: %v", name, e)
		}
	}
	if store.writes != 0 || len(approvals.records) != 0 {
		t.Fatal("failed action had side effects")
	}
}
