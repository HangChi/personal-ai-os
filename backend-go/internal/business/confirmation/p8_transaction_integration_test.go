//go:build integration

package confirmation

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type observingHandler struct {
	run func(context.Context) (int, error)
}

func (*observingHandler) Actions() []Action { return allActions }
func (h *observingHandler) Execute(ctx context.Context, _ Action, _ string) (int, error) {
	return h.run(ctx)
}

func TestPostgresP8ConfirmationTransactionOutcomes(t *testing.T) {
	ctx := context.Background()
	first, _ := NewPostgresStore(fixturePool(t))
	second, _ := NewPostgresStore(fixturePool(t))
	for _, test := range []struct {
		name   string
		err    error
		status Status
	}{
		{"success", nil, Confirmed},
		{"business", api.NewBusinessError(api.AINotConfigured, "fixture unavailable"), Failed},
		{"runtime", errors.New("fixture failed"), Failed},
		{"missing", ErrTargetMissing, Cancelled},
	} {
		t.Run(test.name, func(t *testing.T) {
			var id string
			calls := 0
			h := &observingHandler{run: func(ctx context.Context) (int, error) {
				calls++
				// A different pool must see the committed claim while the handler runs.
				r, found, err := second.Get(ctx, id)
				if err != nil || !found || r.Status != Confirmed || r.ResolvedAt == nil {
					t.Fatalf("claim not independently committed: found=%v state=%s err=%v", found, r.Status, err)
				}
				return 3, test.err
			}}
			registry, _ := NewRegistry(h)
			service, _ := NewService(first, registry)
			pending, err := service.Create(ctx, DeleteTask, "P8 transaction "+test.name, nil, 3, "p8-transaction")
			if err != nil {
				t.Fatal(err)
			}
			id = pending.ID
			if _, err := service.Confirm(ctx, id, "wrong"); err == nil || calls != 0 {
				t.Fatal("wrong token consumed a claim")
			}
			result, err := service.Confirm(ctx, id, pending.Token)
			if !errors.Is(err, test.err) && !errors.Is(test.err, ErrTargetMissing) {
				t.Fatalf("handler error lost: %v", err)
			}
			if test.status == Confirmed && (!result.Executed || result.AffectedCount != 3 || !strings.Contains(result.Summary, "已执行")) {
				t.Fatal("success not returned", result)
			}
			if test.status == Cancelled && (err != nil || result.Executed || result.AffectedCount != 0 || !strings.Contains(result.Summary, "目标已不存在")) {
				t.Fatal("missing target not cancelled", result, err)
			}
			r, found, err := second.Get(ctx, id)
			if err != nil || !found || r.Status != test.status || r.ResolvedAt == nil || calls != 1 {
				t.Fatalf("terminal outcome not persisted: found=%v state=%s calls=%d err=%v", found, r.Status, calls, err)
			}
			if _, err := service.Confirm(ctx, id, pending.Token); err == nil || calls != 1 {
				t.Fatal("terminal token executed again")
			}
		})
	}
	service, _ := NewService(first, mustRegistry(t, &observingHandler{run: func(context.Context) (int, error) {
		t.Fatal("expired confirmation executed")
		return 0, nil
	}}))
	pending, err := service.Create(ctx, DeleteTask, "P8 expired", nil, 1, "p8-transaction-expired")
	if err != nil {
		t.Fatal(err)
	}
	err = first.pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "UPDATE confirmation SET expires_at=$2 WHERE id=$1", pending.ID, time.Now().Add(-time.Minute))
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Confirm(ctx, pending.ID, pending.Token); err == nil {
		t.Fatal("expired claim accepted")
	}
	r, found, err := second.Get(ctx, pending.ID)
	if err != nil || !found || r.Status != Expired || r.ResolvedAt == nil {
		t.Fatal("expiry rolled back", err)
	}
	rows, err := service.List(ctx, "p8-transaction-expired", Expired)
	if err != nil || len(rows) != 1 || rows[0].ID != pending.ID {
		t.Fatal("conversation/status filter", err)
	}
}

func mustRegistry(t *testing.T, handler Handler) *Registry {
	t.Helper()
	registry, err := NewRegistry(handler)
	if err != nil {
		t.Fatal(err)
	}
	return registry
}
