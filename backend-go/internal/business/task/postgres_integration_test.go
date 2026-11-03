//go:build integration

package task

import (
	"context"
	"os"
	"personal-ai-os/backend-go/internal/platform/database"
	"strconv"
	"testing"
	"time"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需独立Flyway测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许独立测试库")
	}
	opts.Port = port
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := database.Open(ctx, connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	})
	return pool
}
func TestPostgresTaskCRUDAndSearch(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	store, _ := NewPostgresStore(fixturePool(t))
	service, _ := NewService(store)
	title := "合成任务P2"
	created, err := service.Create(ctx, CreateRequest{Title: title})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = service.Delete(context.Background(), created.ID) })
	if created.Status != Todo || created.Priority != Medium {
		t.Fatalf("created=%+v", created)
	}
	doing := Doing
	updated, err := service.Update(ctx, created.ID, UpdateRequest{Status: &doing})
	if err != nil || updated.Status != Doing {
		t.Fatalf("updated=%+v err=%v", updated, err)
	}
	done, err := service.SetCompleted(ctx, created.ID, true)
	if err != nil || done.Status != Done || done.PreviousStatus == nil || *done.PreviousStatus != Doing {
		t.Fatalf("done=%+v err=%v", done, err)
	}
	reopened, err := service.SetCompleted(ctx, created.ID, false)
	if err != nil || reopened.Status != Doing {
		t.Fatalf("reopened=%+v err=%v", reopened, err)
	}
	page, err := service.Search(ctx, Query{Keyword: "合成任务P2", Size: 20})
	if err != nil || page.Total < 1 {
		t.Fatalf("page=%+v err=%v", page, err)
	}
	stats, err := service.Stats(ctx)
	if err != nil || stats.Pending < 1 {
		t.Fatalf("stats=%+v err=%v", stats, err)
	}
	if err := service.Delete(ctx, created.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Get(ctx, created.ID); err != ErrNotFound {
		t.Fatalf("after delete=%v", err)
	}
}
