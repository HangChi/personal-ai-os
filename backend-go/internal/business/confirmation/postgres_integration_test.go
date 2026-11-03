//go:build integration

package confirmation

import (
	"context"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
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
func TestPostgresCASAcrossConnections(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	one, _ := NewPostgresStore(fixturePool(t))
	two, _ := NewPostgresStore(fixturePool(t))
	h := &fixtureHandler{actions: allActions}
	registry, _ := NewRegistry(h)
	first, _ := NewService(one, registry)
	second, _ := NewService(two, registry)
	dto, err := first.Create(ctx, DeleteTask, "跨连接删除", map[string]any{"id": "synthetic"}, 1, "fixture")
	if err != nil {
		t.Fatal(err)
	}
	var successful atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			service := first
			if i%2 == 1 {
				service = second
			}
			if result, err := service.Confirm(ctx, dto.ID, dto.Token); err == nil && result.Executed {
				successful.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if successful.Load() != 1 || h.calls.Load() != 1 {
		t.Fatalf("executed=%d handler=%d", successful.Load(), h.calls.Load())
	}
	stored, ok, err := two.Get(ctx, dto.ID)
	if err != nil || !ok || stored.Status != Confirmed || stored.ResolvedAt == nil {
		t.Fatalf("record=%+v found=%v err=%v", stored, ok, err)
	}
}
