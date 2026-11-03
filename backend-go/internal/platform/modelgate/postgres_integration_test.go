//go:build integration

package modelgate

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("需专用临时 PostgreSQL")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host, opts.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username, opts.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("只允许专用测试库")
	}
	opts.Port, opts.SSLMode = port, "disable"
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(true))
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

func fixtureCoordinator(t *testing.T, pool *database.Pool) *coordination.Coordinator {
	t.Helper()
	c, err := coordination.New(pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := c.Close(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return c
}

func TestSharedGateIntegration(t *testing.T) {
	pool := fixturePool(t)
	otherPool := fixturePool(t)
	c, other := fixtureCoordinator(t, pool), fixtureCoordinator(t, otherPool)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	execute := func(query string) {
		t.Helper()
		if err := pool.WithConn(ctx, func(conn *sql.Conn) error { _, err := conn.ExecContext(ctx, query); return err }); err != nil {
			t.Fatal(err)
		}
	}
	ddl, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration", "V37__runtime_coordination.sql"))
	if err != nil {
		t.Fatal(err)
	}
	execute(string(ddl))
	newGate := func(limit int, role config.Role, c *coordination.Coordinator) *Gate {
		t.Helper()
		gate, err := NewShared(limit, role, c)
		if err != nil {
			t.Fatal(err)
		}
		return gate
	}
	acquire := func(gate *Gate) *Lease {
		t.Helper()
		lease, err := gate.TryAcquire(ctx)
		if lease == nil || err != nil {
			t.Fatalf("取得许可失败: %v", err)
		}
		return lease
	}
	release := func(lease *Lease) {
		t.Helper()
		if err := lease.Release(ctx); err != nil {
			t.Fatal(err)
		}
	}
	countBackground := func() int {
		t.Helper()
		var count int
		if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			return conn.QueryRowContext(ctx, "SELECT count(*) FROM runtime_lease WHERE resource='model-background' AND owner IS NOT NULL").Scan(&count)
		}); err != nil {
			t.Fatal(err)
		}
		return count
	}
	for _, role := range []config.Role{config.Index, config.Review, config.Delivery} {
		t.Run(string(role)+" reserves API slot", func(t *testing.T) {
			worker, api := newGate(2, role, c), newGate(2, config.API, other)
			background, foreground := acquire(worker), acquire(api)
			if lease, err := worker.TryAcquire(ctx); lease != nil || err != nil {
				t.Fatal("后台不能使用 API 预留槽")
			}
			another := newGate(2, role, other)
			if lease, err := another.TryAcquire(ctx); lease != nil || err != nil {
				t.Fatal("后台配额必须跨池共享")
			}
			release(background)
			replacement := acquire(another)
			release(replacement)
			release(foreground)
		})
	}
	t.Run("combined has foreground policy and shared denial returns background", func(t *testing.T) {
		api, worker := newGate(2, config.Combined, other), newGate(2, config.Index, c)
		one, two := acquire(api), acquire(api)
		if lease, err := worker.TryAcquire(ctx); lease != nil || err != nil || countBackground() != 0 || worker.inUse.Load() != 0 {
			t.Fatal("共享容量拒绝必须归还后台和本地许可")
		}
		release(one)
		background := acquire(worker)
		release(background)
		release(two)
	})
	t.Run("limit one retains Java behavior", func(t *testing.T) {
		worker, api := newGate(1, config.Index, c), newGate(1, config.API, other)
		lease := acquire(worker)
		if second, err := api.TryAcquire(ctx); second != nil || err != nil {
			t.Fatal("limit=1 时 Java 没有额外预留槽")
		}
		release(lease)
		foreground := acquire(api)
		release(foreground)
	})
	t.Run("failure after background acquire cleans both permits", func(t *testing.T) {
		execute(`CREATE FUNCTION fixture_fail_shared() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
			IF NEW.resource='model-concurrency' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END $$;
			CREATE TRIGGER fixture_fail_shared BEFORE INSERT ON runtime_lease FOR EACH ROW EXECUTE FUNCTION fixture_fail_shared()`)
		worker := newGate(2, config.Index, c)
		lease, err := worker.TryAcquire(ctx)
		execute("DROP TRIGGER fixture_fail_shared ON runtime_lease; DROP FUNCTION fixture_fail_shared()")
		if lease != nil || err == nil || worker.inUse.Load() != 0 || countBackground() != 0 {
			t.Fatal("共享获取异常必须清理已经取得的后台许可")
		}
		replacement := acquire(worker)
		release(replacement)
	})
	t.Run("release failure still returns background and local capacity", func(t *testing.T) {
		worker := newGate(2, config.Index, c)
		lease := acquire(worker)
		execute(`CREATE FUNCTION fixture_fail_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
			IF NEW.resource='model-concurrency' AND NEW.owner IS NULL THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END $$;
			CREATE TRIGGER fixture_fail_release BEFORE UPDATE ON runtime_lease FOR EACH ROW EXECUTE FUNCTION fixture_fail_release()`)
		err := lease.Release(ctx)
		execute("DROP TRIGGER fixture_fail_release ON runtime_lease; DROP FUNCTION fixture_fail_release()")
		if err == nil || worker.inUse.Load() != 0 || countBackground() != 0 {
			t.Fatal("共享释放失败仍须归还后台和本地许可")
		}
		if err := lease.Release(ctx); err != nil || worker.inUse.Load() != 0 {
			t.Fatal("重复释放不能重试或重复归还")
		}
		// 仅复位脚本临时库中的故障注入行；生产释放失败需等待自然过期。
		execute("UPDATE runtime_lease SET owner=NULL,lease_until='epoch'")
	})
}
