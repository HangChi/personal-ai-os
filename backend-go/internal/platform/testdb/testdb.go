//go:build integration

// Package testdb 仅允许显式创建的临时 Flyway 测试库。
package testdb

import (
	"context"
	"os"
	"personal-ai-os/backend-go/internal/platform/database"
	"strconv"
	"testing"
)

func Open(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需独立Flyway测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	opts.SSLMode = "disable"
	if opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许临时测试库")
	}
	var err error
	opts.Port, err = strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil {
		t.Fatal(err)
	}
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pool.Close() })
	return pool
}
