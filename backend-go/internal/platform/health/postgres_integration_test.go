//go:build integration

package health

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

func healthDatabase(t *testing.T, maxOpen int, maintenance bool) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("仅运行本次专用临时 PostgreSQL")
	}
	settings := database.DefaultPostgresOptions()
	settings.Host, settings.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	settings.Username, settings.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || settings.Host != "127.0.0.1" || settings.Database != "personalos_go_test" || settings.Username != "go_fixture" {
		t.Fatal("不是专用回环测试库")
	}
	settings.Port = port
	if maintenance {
		// 只能在已验证的本次临时集群内切换到维护库；不能从当前库禁用自身连接。
		settings.Database = "postgres"
	}
	settings.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	if settings.SSLMode == "" {
		settings.SSLMode = "disable"
	}
	settings.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(settings)
	if err != nil {
		t.Fatal(err)
	}
	options := database.DefaultPoolOptions(true)
	options.MaxOpen, options.MaxIdle = maxOpen, maxOpen
	pool, err := database.Open(context.Background(), connector, options)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pool.Close() })
	return pool
}

func TestPostgresHealthRealHTTPOutageAndRecovery(t *testing.T) {
	pool, admin := healthDatabase(t, 1, false), healthDatabase(t, 1, true)
	probe, err := NewPostgresProbe(pool)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service, err := New(testOptions(), probe, func() bool { return false }, logger)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	client := &http.Client{Timeout: 3 * time.Second}
	report := func(want string) {
		t.Helper()
		response, err := client.Get(server.URL + "/api/v1/health")
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var body api.Response[Status]
		if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != 200 || body.Code != api.Success || body.Data.Status != want || body.Data.Components["database"] != want || body.Data.Components["ai"] != "NOT_CONFIGURED" {
			t.Fatal("真实依赖状态未反映到 HTTP", body)
		}
	}
	report("UP")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// 只操作脚本新建的集群；禁止新连接并终止其他探针连接，得到真实不可用。
	err = admin.WithConn(ctx, func(conn *sql.Conn) error {
		if _, err := conn.ExecContext(ctx, "ALTER DATABASE personalos_go_test ALLOW_CONNECTIONS false"); err != nil {
			return err
		}
		defer func() {
			cleanup, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if _, err := conn.ExecContext(cleanup, "ALTER DATABASE personalos_go_test ALLOW_CONNECTIONS true"); err != nil {
				t.Error("临时集群恢复连接失败")
			}
		}()
		if _, err := conn.ExecContext(ctx, `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='personalos_go_test' AND pid<>pg_backend_pid()`); err != nil {
			return err
		}
		report("DOWN")
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	report("UP")
	if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		var tables int
		if err := conn.QueryRowContext(ctx, "SELECT count(*) FROM pg_tables WHERE schemaname='public'").Scan(&tables); err != nil {
			return err
		}
		if tables != 0 {
			return errors.New("健康探测不应创建业务表")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestPostgresHealthPoolSaturationDeadline(t *testing.T) {
	pool := healthDatabase(t, 1, false)
	probe, err := NewPostgresProbe(pool)
	if err != nil {
		t.Fatal(err)
	}
	options := testOptions()
	options.ProbeTimeout = 50 * time.Millisecond
	service, _ := New(options, probe, func() bool { return true }, slog.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err = pool.WithConn(ctx, func(_ *sql.Conn) error {
		start := time.Now()
		if status := service.CurrentStatus(ctx); status.Status != "DOWN" || time.Since(start) > time.Second {
			return errors.New("池饱和探测未受限或误报健康")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if service.CurrentStatus(ctx).Status != "UP" || pool.Stats().InUse != 0 {
		t.Fatal("池饱和后未恢复或泄漏连接")
	}
	if _, err := NewPostgresProbe(nil); err == nil {
		t.Fatal("接受空连接池")
	}
}
