package confirmation

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

type failingStore struct {
	*fixtureStore
	getErr, listErr             error
	casFault, casMiss, casCalls int
}

func (s *failingStore) Get(c context.Context, id string) (Record, bool, error) {
	if s.getErr != nil {
		return Record{}, false, s.getErr
	}
	return s.fixtureStore.Get(c, id)
}
func (s *failingStore) List(c context.Context, id string, status Status) ([]Record, error) {
	if s.listErr != nil {
		return nil, s.listErr
	}
	return s.fixtureStore.List(c, id, status)
}
func (s *failingStore) CompareAndSet(c context.Context, id string, from, to Status, at time.Time) (bool, error) {
	s.casCalls++
	if s.casCalls == s.casFault {
		return false, errors.New("CAS storage fault")
	}
	if s.casCalls == s.casMiss {
		return false, nil
	}
	return s.fixtureStore.CompareAndSet(c, id, from, to, at)
}
func TestConfirmationStorageFailuresNeverRepeatAnExecutedAction(t *testing.T) {
	ctx := context.Background()
	if _, e := NewRegistry(nil); e == nil {
		t.Fatal("nil handler")
	}
	if _, e := NewService(nil, nil); e == nil {
		t.Fatal("nil service")
	}
	for _, mode := range []string{"get", "list", "expired", "claim", "claim-miss", "outcome", "outcome-miss", "cancel", "cancel-miss", "cancel-token"} {
		t.Run(mode, func(t *testing.T) {
			h := &fixtureHandler{actions: allActions}
			registry, _ := NewRegistry(h)
			store := &failingStore{fixtureStore: newStore()}
			service, _ := NewService(store, registry)
			dto, e := service.Create(ctx, DeleteTask, "删除", nil, 1, "c")
			if e != nil {
				t.Fatal(e)
			}
			switch mode {
			case "get":
				store.getErr = errors.New("read")
			case "list":
				store.listErr = errors.New("list")
			case "expired":
				service.now = func() time.Time { return dto.ExpiresAt.Add(time.Second) }
				store.casFault = 1
			case "claim", "cancel":
				store.casFault = 1
			case "claim-miss", "cancel-miss":
				store.casMiss = 1
			case "outcome":
				store.casFault = 2
			case "outcome-miss":
				store.casMiss = 2
			}
			if mode == "list" {
				_, e = service.List(ctx, "c", Pending)
			} else if strings.HasPrefix(mode, "cancel") {
				token := dto.Token
				if mode == "cancel-token" {
					token = "bad"
				}
				e = service.Cancel(ctx, dto.ID, token)
			} else {
				_, e = service.Confirm(ctx, dto.ID, dto.Token)
			}
			if e == nil {
				t.Fatal("failure swallowed")
			}
			if e.Error() == "" {
				t.Fatal("empty diagnostic")
			}
			expected := int32(0)
			if strings.HasPrefix(mode, "outcome") {
				expected = 1
			}
			if h.calls.Load() != expected {
				t.Fatal("unexpected execution count", h.calls.Load())
			}
		})
	}
	service, _, _ := fixture(t)
	for _, tc := range []struct {
		action  Action
		summary string
		payload any
		count   int
	}{{Action("unknown"), "x", nil, 1}, {DeleteTask, "", nil, 1}, {DeleteTask, "x", nil, -1}, {DeleteTask, "x", make(chan int), 1}} {
		if _, e := service.Create(ctx, tc.action, tc.summary, tc.payload, tc.count, "c"); e == nil {
			t.Fatal("invalid create")
		}
	}
}
