package confirmation

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureHandler struct {
	actions []Action
	calls   atomic.Int32
	err     error
}

func (h *fixtureHandler) Actions() []Action { return h.actions }
func (h *fixtureHandler) Execute(_ context.Context, _ Action, _ string) (int, error) {
	h.calls.Add(1)
	return 2, h.err
}

type fixtureStore struct {
	mu   sync.Mutex
	rows map[string]Record
	next int
}

func newStore() *fixtureStore { return &fixtureStore{rows: make(map[string]Record)} }
func (s *fixtureStore) Create(_ context.Context, r Record) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.next++
	r.ID = string(rune('a' + s.next))
	s.rows[r.ID] = r
	return r, nil
}
func (s *fixtureStore) Get(_ context.Context, id string) (Record, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rows[id]
	return r, ok, nil
}
func (s *fixtureStore) List(_ context.Context, conversation string, status Status) ([]Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var rows []Record
	for _, r := range s.rows {
		if r.Status == status && (conversation == "" || conversation == r.ConversationID) {
			rows = append(rows, r)
		}
	}
	return rows, nil
}
func (s *fixtureStore) CompareAndSet(_ context.Context, id string, expected, next Status, now time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rows[id]
	if !ok || r.Status != expected {
		return false, nil
	}
	r.Status = next
	r.ResolvedAt = &now
	s.rows[id] = r
	return true, nil
}
func fixture(t *testing.T) (*Service, *fixtureHandler, *fixtureStore) {
	t.Helper()
	h := &fixtureHandler{actions: allActions}
	r, err := NewRegistry(h)
	if err != nil {
		t.Fatal(err)
	}
	s := newStore()
	service, err := NewService(s, r)
	if err != nil {
		t.Fatal(err)
	}
	return service, h, s
}
func TestRegistryRejectsMissingAndDuplicate(t *testing.T) {
	if _, err := NewRegistry(&fixtureHandler{actions: []Action{DeleteTask}}); err == nil {
		t.Fatal("missing action accepted")
	}
	if _, err := NewRegistry(&fixtureHandler{actions: allActions}, &fixtureHandler{actions: []Action{DeleteTask}}); err == nil {
		t.Fatal("duplicate accepted")
	}
}

func TestTaskStageRegistry(t *testing.T) {
	registry, err := NewRegistryFor([]Action{DeleteTask, ScheduleTask, SetPriority}, &fixtureHandler{actions: []Action{DeleteTask, ScheduleTask, SetPriority}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Require(MCPToolCall); err == nil {
		t.Fatal("unregistered action accepted")
	}
}
func TestLifecycleTokenExpiryAndOutcome(t *testing.T) {
	ctx := context.Background()
	service, h, store := fixture(t)
	dto, err := service.Create(ctx, DeleteTask, "删除两个任务", map[string]any{"ids": []string{"1", "2"}}, 2, "conversation")
	if err != nil || len(dto.Token) != 48 || dto.Status != Pending || dto.ExpiresAt.Sub(dto.CreatedAt) != Validity {
		t.Fatalf("create=%+v err=%v", dto, err)
	}
	if _, err = service.Confirm(ctx, dto.ID, "wrong"); err == nil {
		t.Fatal("wrong token accepted")
	}
	result, err := service.Confirm(ctx, dto.ID, dto.Token)
	if err != nil || !result.Executed || result.AffectedCount != 2 || h.calls.Load() != 1 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if _, err = service.Confirm(ctx, dto.ID, dto.Token); err == nil {
		t.Fatal("repeated confirmation accepted")
	}
	if store.rows[dto.ID].Status != Confirmed {
		t.Fatal("state not committed")
	}
	expired, err := service.Create(ctx, DeleteTask, "过期", nil, 0, "")
	if err != nil {
		t.Fatal(err)
	}
	service.now = func() time.Time { return expired.ExpiresAt.Add(time.Second) }
	if _, err = service.Confirm(ctx, expired.ID, expired.Token); err == nil || store.rows[expired.ID].Status != Expired {
		t.Fatalf("expiry err=%v state=%s", err, store.rows[expired.ID].Status)
	}
}
func TestConcurrentConsumptionAndHandlerFailure(t *testing.T) {
	service, h, store := fixture(t)
	ctx := context.Background()
	dto, _ := service.Create(ctx, DeleteTask, "删除", nil, 1, "")
	var wg sync.WaitGroup
	var success atomic.Int32
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := service.Confirm(ctx, dto.ID, dto.Token); err == nil {
				success.Add(1)
			}
		}()
	}
	wg.Wait()
	if success.Load() != 1 || h.calls.Load() != 1 {
		t.Fatalf("success=%d handler=%d", success.Load(), h.calls.Load())
	}
	h.err = ErrTargetMissing
	missing, _ := service.Create(ctx, DeleteTask, "删除", nil, 1, "")
	result, err := service.Confirm(ctx, missing.ID, missing.Token)
	if err != nil || result.Executed || store.rows[missing.ID].Status != Cancelled {
		t.Fatalf("missing result=%+v err=%v", result, err)
	}
	h.err = errors.New("network error")
	failed, _ := service.Create(ctx, MCPToolCall, "远端操作", nil, 1, "")
	_, err = service.Confirm(ctx, failed.ID, failed.Token)
	if err == nil || store.rows[failed.ID].Status != Failed {
		t.Fatalf("failed err=%v state=%s", err, store.rows[failed.ID].Status)
	}
	cancelled, _ := service.Create(ctx, DeleteTask, "取消", nil, 1, "")
	if err = service.Cancel(ctx, cancelled.ID, cancelled.Token); err != nil || store.rows[cancelled.ID].Status != Cancelled {
		t.Fatalf("cancel err=%v", err)
	}
}
