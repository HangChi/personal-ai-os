//go:build integration

package confirmation

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

func TestPostgresP8CancelledUnknownAndFilteredConfirmations(t *testing.T) {
	ctx := context.Background()
	store, _ := NewPostgresStore(fixturePool(t))
	handler := &fixtureHandler{actions: allActions}
	service, _ := NewService(store, mustRegistry(t, handler))
	conversation := fmt.Sprintf("p8-lifecycle-%d", time.Now().UnixNano())
	created := []DTO{}
	for _, name := range []string{conversation, conversation, conversation + "-other"} {
		pending, err := service.Create(ctx, DeleteTask, "P8 lifecycle", nil, 1, name)
		if err != nil {
			t.Fatal(err)
		}
		created = append(created, pending)
		t.Cleanup(func() {
			_ = store.pool.WithConn(context.Background(), func(conn *sql.Conn) error {
				_, err := conn.ExecContext(context.Background(), "DELETE FROM confirmation WHERE id=$1", pending.ID)
				return err
			})
		})
	}
	if err := service.Cancel(ctx, created[0].ID, created[0].Token); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Confirm(ctx, created[0].ID, created[0].Token); err == nil || handler.calls.Load() != 0 {
		t.Fatal("cancelled confirmation executed", err)
	}
	for _, test := range []struct {
		status Status
		id     string
	}{{Pending, created[1].ID}, {Cancelled, created[0].ID}, {"", created[1].ID}} {
		rows, err := service.List(ctx, conversation, test.status)
		if err != nil || len(rows) != 1 || rows[0].ID != test.id {
			t.Fatal("conversation/status filter", test.status, err)
		}
	}
	_, err := service.Confirm(ctx, "00000000-0000-0000-0000-000000000000", "unknown")
	var problem *Error
	if !errors.As(err, &problem) || problem.Code != api.ResourceNotFound || handler.calls.Load() != 0 {
		t.Fatal("unknown confirmation contract", err)
	}
}
