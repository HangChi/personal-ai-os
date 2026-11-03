//go:build integration

package idempotency

import (
	"context"
	"database/sql"
	"errors"
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
		t.Skip("需脚本创建并迁移的独立 PostgreSQL")
	}
	options := database.DefaultPostgresOptions()
	options.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	options.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	options.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	options.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || options.Host != "127.0.0.1" || options.Database != "personalos_go_test" || options.Username != "go_fixture" {
		t.Fatal("仅允许脚本创建的专用回环测试库")
	}
	options.Port = port
	options.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	options.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(options)
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

func TestPostgresCrossConnectionOwnershipAndReplay(t *testing.T) {
	first, second := fixturePool(t), fixturePool(t)
	stores := make([]*PostgresStore, 2)
	var err error
	stores[0], err = NewPostgresStore(first)
	if err != nil {
		t.Fatal(err)
	}
	stores[1], err = NewPostgresStore(second)
	if err != nil {
		t.Fatal(err)
	}
	services := make([]*Service, 2)
	services[0], _ = NewService(stores[0])
	services[1], _ = NewService(stores[1])
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := first.WithConn(ctx, func(c *sql.Conn) error {
		_, err := c.ExecContext(ctx, "DELETE FROM operation_record WHERE idempotency_key LIKE 'go-p2-%'")
		return err
	}); err != nil {
		t.Fatal(err)
	}
	digest := SHA256("并发创建")
	payload := `{"id":"one"}`
	var calls atomic.Int32
	start := make(chan struct{})
	release := make(chan struct{})
	action := func(context.Context) (Result, error) {
		if calls.Add(1) == 1 {
			close(start)
		}
		<-release
		return NewResult(&payload, Success), nil
	}
	var wg sync.WaitGroup
	wg.Add(1)
	firstErr := make(chan error, 1)
	go func() {
		defer wg.Done()
		_, err := services[0].Execute(ctx, request("go-p2-race", SourceHTTP, digest), action)
		firstErr <- err
	}()
	<-start
	_, raceErr := services[1].Execute(ctx, request("go-p2-race", SourceHTTP, digest), action)
	var conflict *ConflictError
	if !errors.As(raceErr, &conflict) || conflict.Kind != ErrInProgress {
		t.Fatalf("竞争请求应看到结果不明保护: %v", raceErr)
	}
	close(release)
	wg.Wait()
	if err := <-firstErr; err != nil {
		t.Fatal(err)
	}
	replayed, err := services[1].Execute(ctx, request("go-p2-race", SourceHTTP, digest), action)
	if err != nil || !replayed.Replayed || *replayed.ResponsePayload != payload || calls.Load() != 1 {
		t.Fatalf("replay=%+v calls=%d err=%v", replayed, calls.Load(), err)
	}
	var rows int
	if err := first.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT count(*) FROM operation_record WHERE idempotency_key='go-p2-race' AND outcome='SUCCESS'").Scan(&rows)
	}); err != nil || rows != 1 {
		t.Fatalf("rows=%d err=%v", rows, err)
	}
}
