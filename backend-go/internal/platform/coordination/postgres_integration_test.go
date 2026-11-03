//go:build integration

package coordination

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

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

func fixtureCoordinator(t *testing.T, pool *database.Pool) *Coordinator {
	t.Helper()
	c, err := New(pool)
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

func TestPostgresLeaseIntegration(t *testing.T) {
	firstPool, secondPool := fixturePool(t), fixturePool(t)
	first, second := fixtureCoordinator(t, firstPool), fixtureCoordinator(t, secondPool)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	ddl, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration", "V37__runtime_coordination.sql"))
	if err != nil {
		t.Fatal(err)
	}
	execute := func(query string, args ...any) {
		t.Helper()
		if err := firstPool.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, query, args...); return err }); err != nil {
			t.Fatal(err)
		}
	}
	execute(string(ddl))
	acquire := func(c *Coordinator, name string, capacity int) *Lease {
		t.Helper()
		lease, err := c.Acquire(ctx, name, capacity)
		if err != nil || lease == nil {
			t.Fatalf("获取租约失败: %v", err)
		}
		return lease
	}
	release := func(lease *Lease) {
		t.Helper()
		if err := lease.Release(ctx); err != nil {
			t.Fatal(err)
		}
	}
	fence := func(lease *Lease) error {
		return firstPool.WithManagedTx(ctx, sql.TxOptions{}, func(tx *database.Transaction) error { return lease.Fence(ctx, tx) })
	}
	t.Run("shared capacity and idempotent release", func(t *testing.T) {
		lease := acquire(first, "shared", 1)
		if other, err := second.Acquire(ctx, "shared", 1); other != nil || err != nil {
			t.Fatal("两个池必须共享容量")
		}
		release(lease)
		release(lease)
		other := acquire(second, "shared", 1)
		release(other)
	})
	t.Run("expired owner cannot write or release replacement", func(t *testing.T) {
		old := acquire(first, "expired", 1)
		execute("UPDATE runtime_lease SET lease_until=clock_timestamp()-interval '1 second' WHERE resource='expired'")
		replacement := acquire(second, "expired", 1)
		if !errors.Is(fence(old), ErrLeaseLost) {
			t.Fatal("过期所有者不能通过 fencing")
		}
		release(old)
		if err := fence(replacement); err != nil {
			t.Fatal(err)
		}
		if other, err := first.Acquire(ctx, "expired", 1); other != nil || err != nil {
			t.Fatal("旧所有者不能释放新租约")
		}
		release(replacement)
	})
	t.Run("renewal never resurrects invalid local ownership", func(t *testing.T) {
		lease := acquire(first, "renew", 1)
		if err := first.Renew(ctx); err != nil || !lease.Valid() {
			t.Fatal(err)
		}
		execute("UPDATE runtime_lease SET lease_until=clock_timestamp()-interval '1 second' WHERE resource='renew'")
		if err := first.Renew(ctx); err != nil || lease.Valid() {
			t.Fatal("过期续租必须失效")
		}
		execute("UPDATE runtime_lease SET lease_until=clock_timestamp()+interval '120 seconds' WHERE resource='renew'")
		if err := first.Renew(ctx); err != nil || lease.Valid() {
			t.Fatal("后续成功不能恢复已失去的本地信任")
		}
		release(lease)
	})
	for _, mode := range []string{"commit", "rollback", "panic"} {
		t.Run("deferred release "+mode, func(t *testing.T) {
			lease := acquire(first, "deferred-"+mode, 1)
			failure := errors.New("fixture rollback")
			func() {
				defer func() {
					p := recover()
					if mode == "panic" {
						if p != "fixture panic" {
							t.Fatal("panic 未传播")
						}
					} else if p != nil {
						panic(p)
					}
				}()
				err := firstPool.WithManagedTx(ctx, sql.TxOptions{}, func(tx *database.Transaction) error {
					if err := lease.Fence(ctx, tx); err != nil {
						return err
					}
					if err := lease.ReleaseAfter(tx); err != nil {
						return err
					}
					if lease.Valid() {
						return errors.New("登记释放后不能继续信任租约")
					}
					if err := lease.Release(ctx); err != nil {
						return err
					}
					var owned bool
					if err := tx.SQL().QueryRowContext(ctx, "SELECT owner IS NOT NULL FROM runtime_lease WHERE resource=$1", lease.resource).Scan(&owned); err != nil {
						return err
					}
					if !owned {
						return errors.New("事务完成前不得释放数据库所有权")
					}
					if mode == "panic" {
						panic("fixture panic")
					}
					if mode == "rollback" {
						return failure
					}
					return nil
				})
				if mode == "rollback" {
					if !errors.Is(err, failure) {
						t.Fatal(err)
					}
				} else if err != nil {
					t.Fatal(err)
				}
			}()
			replacement := acquire(second, "deferred-"+mode, 1)
			release(replacement)
		})
	}
	t.Run("fencing rejects missing read-only and finished transaction", func(t *testing.T) {
		lease := acquire(first, "tx-required", 1)
		defer release(lease)
		if lease.Fence(ctx, nil) != ErrWriteTransactionRequired {
			t.Fatal("没有事务不能 fencing")
		}
		var saved *database.Transaction
		err := firstPool.WithManagedTx(ctx, sql.TxOptions{ReadOnly: true}, func(tx *database.Transaction) error { saved = tx; return lease.Fence(ctx, tx) })
		if !errors.Is(err, ErrWriteTransactionRequired) || lease.Fence(ctx, saved) != ErrWriteTransactionRequired {
			t.Fatal("只读或结束事务不能 fencing")
		}
		if lease.ReleaseAfter(saved) == nil || !lease.Valid() {
			t.Fatal("注册失败不得提前关闭租约")
		}
	})
	t.Run("concurrent initialization is bounded", func(t *testing.T) {
		var group sync.WaitGroup
		leases := make(chan *Lease, 32)
		failures := make(chan error, 32)
		for i := range 32 {
			c := []*Coordinator{first, second}[i%2]
			group.Go(func() {
				lease, err := c.Acquire(ctx, "concurrent", 4)
				if err != nil {
					failures <- err
				}
				if lease != nil {
					leases <- lease
				}
			})
		}
		group.Wait()
		close(leases)
		close(failures)
		for err := range failures {
			t.Error(err)
		}
		if len(leases) != 4 {
			t.Fatalf("容量 4 必须只发出 4 个租约，实际 %d", len(leases))
		}
		for lease := range leases {
			release(lease)
		}
	})
	t.Run("SKIP LOCKED chooses free unlocked slot", func(t *testing.T) {
		lease := acquire(first, "skip-locked", 2)
		release(lease)
		err := firstPool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, "SELECT slot FROM runtime_lease WHERE resource='skip-locked' AND slot=0 FOR UPDATE"); err != nil {
				return err
			}
			bounded, cancel := context.WithTimeout(ctx, time.Second)
			defer cancel()
			other, err := second.Acquire(bounded, "skip-locked", 2)
			if err != nil {
				return err
			}
			if other == nil || other.slot != 1 {
				return errors.New("必须跳过锁定的空闲 slot")
			}
			return other.Release(bounded)
		})
		if err != nil {
			t.Fatal(err)
		}
	})
	t.Run("database failure invalidates held leases", func(t *testing.T) {
		lease := acquire(first, "backend-failure", 1)
		execute("ALTER TABLE runtime_lease RENAME TO fixture_saved_lease")
		err := first.Renew(ctx)
		execute("ALTER TABLE fixture_saved_lease RENAME TO runtime_lease")
		if err == nil || lease.Valid() {
			t.Fatal("不能确认续租必须撤销信任")
		}
		release(lease)
	})
	t.Run("automatic ten second renewal and shutdown", func(t *testing.T) {
		lease := acquire(first, "automatic", 1)
		execute("UPDATE runtime_lease SET lease_until=clock_timestamp()+interval '15 seconds' WHERE resource='automatic'")
		select {
		case <-time.After(11 * time.Second):
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
		var remaining float64
		if err := firstPool.WithConn(ctx, func(c *sql.Conn) error {
			return c.QueryRowContext(ctx, "SELECT extract(epoch FROM lease_until-clock_timestamp()) FROM runtime_lease WHERE resource='automatic'").Scan(&remaining)
		}); err != nil || remaining < 100 || !lease.Valid() {
			t.Fatal("真实定时续租没有延长租期")
		}
		if err := first.Close(ctx); err != nil {
			t.Fatal(err)
		}
		if lease.Valid() {
			t.Fatal("关闭协调器必须撤销所有租约")
		}
		if _, err := first.Acquire(ctx, "closed", 1); !errors.Is(err, ErrClosed) {
			t.Fatal("关闭后不得再获取")
		}
		replacement := acquire(second, "automatic", 1)
		release(replacement)
	})
}
