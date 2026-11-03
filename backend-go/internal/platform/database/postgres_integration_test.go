//go:build integration

package database

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// 测试只接受专用临时库；标准 go test 不会连接任何数据库。
func fixturePostgres(t *testing.T, options PoolOptions) *Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("需显式独立 PostgreSQL 测试环境")
	}
	settings := DefaultPostgresOptions()
	settings.Host, settings.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	settings.Username, settings.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil {
		t.Fatal("测试端口无效")
	}
	settings.Port, settings.SSLMode = port, "disable"
	if settings.Host != "127.0.0.1" || settings.Database != "personalos_go_test" || settings.Username != "go_fixture" {
		t.Fatal("集成测试只允许专用回环测试库与用户")
	}
	connector, err := NewPostgresConnector(settings)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := Open(ctx, connector, options)
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

func TestPostgresIntegrationTransactionsAndTypes(t *testing.T) {
	pool := fixturePostgres(t, DefaultPoolOptions(true))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := pool.WithConn(ctx, func(connection *sql.Conn) error {
		_, err := connection.ExecContext(ctx, "CREATE TABLE go_pool_probe(id integer PRIMARY KEY, value text NOT NULL)")
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	count := func(id int) int {
		t.Helper()
		var count int
		if err := pool.WithConn(ctx, func(c *sql.Conn) error {
			return c.QueryRowContext(ctx, "SELECT count(*) FROM go_pool_probe WHERE id=$1", id).Scan(&count)
		}); err != nil {
			t.Fatal(err)
		}
		return count
	}
	t.Run("commit and rollback", func(t *testing.T) {
		err := pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, "INSERT INTO go_pool_probe VALUES($1,$2)", 1, "committed")
			return err
		})
		if err != nil || count(1) != 1 {
			t.Fatal("提交数据必须可见")
		}
		business := errors.New("fixture business failure")
		err = pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, "INSERT INTO go_pool_probe VALUES($1,$2)", 2, "rollback"); err != nil {
				return err
			}
			return business
		})
		if !errors.Is(err, business) || count(2) != 0 {
			t.Fatal("业务失败必须真实回滚")
		}
	})
	t.Run("panic rollback", func(t *testing.T) {
		func() {
			defer func() {
				if recover() != "fixture panic" {
					t.Fatal("panic 应传播")
				}
			}()
			_ = pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
				if _, err := tx.ExecContext(ctx, "INSERT INTO go_pool_probe VALUES(3,'panic')"); err != nil {
					t.Fatal(err)
				}
				panic("fixture panic")
			})
		}()
		if count(3) != 0 {
			t.Fatal("panic 写入不能提交")
		}
	})
	t.Run("read only and serializable", func(t *testing.T) {
		err := pool.WithTx(ctx, sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, "INSERT INTO go_pool_probe VALUES(4,'read only')")
			return err
		})
		var postgresError *pgconn.PgError
		if !errors.As(err, &postgresError) || postgresError.Code != "25006" || count(4) != 0 {
			t.Fatal("PostgreSQL 必须实际拒绝只读写入")
		}
		err = pool.WithTx(ctx, sql.TxOptions{Isolation: sql.LevelSerializable}, func(tx *sql.Tx) error {
			var isolation string
			if err := tx.QueryRowContext(ctx, "SHOW transaction_isolation").Scan(&isolation); err != nil {
				return err
			}
			if isolation != "serializable" {
				return errors.New("数据库实际隔离级别不一致")
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	})
	t.Run("UTC unicode JSON and UUID", func(t *testing.T) {
		err := pool.WithConn(ctx, func(c *sql.Conn) error {
			var zone, application, uuid string
			var data []byte
			var instant time.Time
			if err := c.QueryRowContext(ctx, "SHOW timezone").Scan(&zone); err != nil {
				return err
			}
			if err := c.QueryRowContext(ctx, "SELECT current_setting('application_name'), gen_random_uuid()::text, $1::jsonb, $2::timestamptz", `{"text":"中文😀"}`, "2026-11-03T12:34:56+08:00").Scan(&application, &uuid, &data, &instant); err != nil {
				return err
			}
			if zone != "UTC" || application != "personalos-go" || len(uuid) != 36 || !strings.Contains(string(data), "中文😀") || instant.Location() != time.UTC || instant.Hour() != 4 {
				return errors.New("数据库时间/Unicode/JSON/UUID 类型不一致")
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	})
	t.Run("canceled transaction never commits", func(t *testing.T) {
		parent, stop := context.WithCancel(ctx)
		err := pool.WithTx(parent, sql.TxOptions{}, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(parent, "INSERT INTO go_pool_probe VALUES(5,'cancel')"); err != nil {
				return err
			}
			stop()
			return nil
		})
		stop()
		if !errors.Is(err, context.Canceled) || count(5) != 0 {
			t.Fatal("取消事务不能留下提交数据")
		}
	})
}

func TestPostgresIntegrationStatementCancellationAndRecovery(t *testing.T) {
	pool := fixturePostgres(t, DefaultPoolOptions(true))
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	started := time.Now()
	err := pool.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, "SELECT pg_sleep(30)"); return err })
	if err == nil || !errors.Is(err, context.DeadlineExceeded) || time.Since(started) > 3*time.Second {
		t.Fatal("驱动必须及时响应查询取消")
	}
	if err := pool.Ping(context.Background()); err != nil {
		t.Fatal("取消后池应恢复可用连接")
	}
}

func TestPostgresIntegrationConcurrentPoolBound(t *testing.T) {
	pool := fixturePostgres(t, DefaultPoolOptions(true))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	var active, maximum atomic.Int32
	var group sync.WaitGroup
	errors := make(chan error, 32)
	for range 32 {
		group.Go(func() {
			errors <- pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
				now := active.Add(1)
				defer active.Add(-1)
				for old := maximum.Load(); now > old && !maximum.CompareAndSwap(old, now); old = maximum.Load() {
				}
				_, err := tx.ExecContext(ctx, "SELECT pg_sleep(0.01)")
				return err
			})
		})
	}
	group.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	if maximum.Load() > 4 || maximum.Load() < 2 || pool.Stats().OpenConnections > 4 || pool.Stats().InUse != 0 {
		t.Fatal("真实并发连接必须遵守 Worker 上限并全部释放")
	}
}
