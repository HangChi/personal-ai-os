package task

import (
	"context"
	"errors"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"testing"
	"time"
)

type boundaryTaskStore struct {
	httpTaskStore
	missing bool
	deletes int
}

func (s *boundaryTaskStore) Get(context.Context, string) (Task, bool, error) {
	return s.task, !s.missing, s.fail
}
func (s *boundaryTaskStore) DeleteAll(context.Context, []string) (int, error) {
	return s.deletes, s.fail
}
func (s *boundaryTaskStore) DueBetween(context.Context, time.Time, time.Time) ([]Task, error) {
	return []Task{s.task}, s.fail
}
func (s *boundaryTaskStore) ListAll(context.Context) ([]Task, error) { return []Task{s.task}, s.fail }
func (s *boundaryTaskStore) CountAll(context.Context) (int64, error) { return 1, s.fail }
func TestTaskServiceValidationAndDelegationBoundaries(t *testing.T) {
	ctx := context.Background()
	_, seed := taskHTTPContract(t)
	store := &boundaryTaskStore{httpTaskStore: *seed}
	s, e := NewService(store)
	if e != nil {
		t.Fatal(e)
	}
	if _, e := NewService(nil); e == nil {
		t.Fatal("nil store")
	}
	badPriority := Priority("invalid")
	for _, v := range []CreateRequest{{Title: "t", Priority: &badPriority}} {
		if _, e := s.Create(ctx, v); e == nil {
			t.Fatal(v)
		}
	}
	for _, v := range []UpdateRequest{{Title: taskBoundaryPtr(" ")}} {
		if _, e := s.Update(ctx, bindingTaskID, v); e == nil {
			t.Fatal(v)
		}
	}
	now := time.Now()
	id := bindingTaskID
	zone := "Asia/Shanghai"
	v, e := s.Update(ctx, id, UpdateRequest{Description: taskBoundaryPtr("detail"), DueAt: &now, ParentTaskID: &id, RecurrenceTimezone: &zone})
	if e != nil || v.Description == nil || v.ParentTaskID == nil || v.DueAt == nil {
		t.Fatal(v, e)
	}
	badStatus := Status("invalid")
	store.task.PreviousStatus = &badStatus
	if _, e := s.SetCompleted(ctx, id, false); e == nil {
		t.Fatal("invalid persisted status")
	}
	if n, e := s.DeleteAll(ctx, []string{" ", ""}); e != nil || n != 0 {
		t.Fatal(n, e)
	}
	for _, p := range [][2]int{{-1, 1}, {0, 0}} {
		if _, e := s.ExportBatch(ctx, p[0], p[1]); e == nil {
			t.Fatal(p)
		}
	}
	if rows, e := s.SearchHits(ctx, "", 0); e != nil || len(rows) != 0 {
		t.Fatal(rows, e)
	}
	if rows, e := s.FindByIDs(ctx, nil); e != nil || len(rows) != 0 {
		t.Fatal(rows, e)
	}
	if rows, e := s.FindDueBetween(ctx, now, now); e != nil || len(rows) != 1 {
		t.Fatal(rows, e)
	}
	if rows, e := s.ListAll(ctx); e != nil || len(rows) != 1 {
		t.Fatal(rows, e)
	}
	if n, e := s.CountAll(ctx); e != nil || n != 1 {
		t.Fatal(n, e)
	}
	store.fail = errors.New("synthetic")
	if _, e := s.Reschedule(ctx, id, nil); e == nil {
		t.Fatal("get failure")
	}
	if _, e := s.ChangePriority(ctx, id, High); e == nil {
		t.Fatal("get failure")
	}
	if _, e := s.ChangePriority(ctx, id, badPriority); e == nil {
		t.Fatal("invalid priority")
	}
}
func TestTaskConfirmationMalformedPayloadAndMissingTarget(t *testing.T) {
	ctx := context.Background()
	_, seed := taskHTTPContract(t)
	store := &boundaryTaskStore{httpTaskStore: *seed, missing: true}
	s, e := NewService(store)
	if e != nil {
		t.Fatal(e)
	}
	h, e := NewConfirmationHandler(s)
	if e != nil {
		t.Fatal(e)
	}
	if _, e := NewConfirmationHandler(nil); e == nil {
		t.Fatal("nil tasks")
	}
	for _, a := range h.Actions() {
		if _, e := h.Execute(ctx, a, "{"); e == nil {
			t.Fatal(a)
		}
	}
	for _, a := range h.Actions() {
		payload := `{"taskId":"` + bindingTaskID + `","priority":"HIGH","taskIds":["` + bindingTaskID + `"]}`
		if _, e := h.Execute(ctx, a, payload); !errors.Is(e, confirmation.ErrTargetMissing) {
			t.Fatal(a, e)
		}
	}
	if _, e := h.Execute(ctx, confirmation.Action("unknown"), "{}"); e == nil {
		t.Fatal("unknown")
	}
	store.fail = errors.New("synthetic")
	if _, e := h.Execute(ctx, confirmation.DeleteTask, `{"taskIds":["`+bindingTaskID+`"]}`); e == nil {
		t.Fatal("delete failure")
	}
	r := &ConfirmationRequests{tasks: s}
	if _, e := NewConfirmationRequests(nil, nil); e == nil {
		t.Fatal("nil dependencies")
	}
	for _, ids := range [][]string{nil, make([]string, 51), {bindingTaskID}} {
		if _, e := r.Delete(ctx, ids, ""); e == nil {
			t.Fatal("invalid request")
		}
	}
	if _, e := r.Schedule(ctx, bindingTaskID, nil, ""); e == nil {
		t.Fatal("get failure")
	}
	if _, e := r.Priority(ctx, bindingTaskID, High, ""); e == nil {
		t.Fatal("get failure")
	}
}

func taskBoundaryPtr[T any](v T) *T { return &v }
