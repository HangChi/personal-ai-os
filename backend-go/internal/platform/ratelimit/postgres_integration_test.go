//go:build integration

package ratelimit

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"personal-ai-os/backend-go/internal/platform/database"
)

func fixtureBucketPool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("需显式独立 PostgreSQL 测试环境")
	}
	options := database.DefaultPostgresOptions()
	options.Host, options.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	options.Username, options.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || options.Host != "127.0.0.1" || options.Database != "personalos_go_test" || options.Username != "go_fixture" {
		t.Fatal("仅允许脚本创建的专用回环测试库")
	}
	options.Port, options.SSLMode = port, "disable"
	if mode := os.Getenv("GO_TEST_POSTGRES_SSL_MODE"); mode != "" {
		options.SSLMode = mode
		options.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	}
	connector, err := database.NewPostgresConnector(options)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := database.Open(ctx, connector, database.DefaultPoolOptions(true))
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

func fixtureBucket(t *testing.T, pool *database.Pool) *PostgresBucket {
	t.Helper()
	bucket, err := NewPostgresBucket(pool)
	if err != nil {
		t.Fatal(err)
	}
	return bucket
}

func TestPostgresBucketIntegration(t *testing.T) {
	first, second := fixtureBucketPool(t), fixtureBucketPool(t)
	one, two := fixtureBucket(t, first), fixtureBucket(t, second)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	sqlFile, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration", "V37__runtime_coordination.sql"))
	if err != nil {
		t.Fatal(err)
	}
	// 使用未修改的 Java 原迁移；不复制 schema 或创建 Flyway 历史。
	if err := first.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, string(sqlFile)); return err }); err != nil {
		t.Fatal(err)
	}
	execute := func(statement string, args ...any) {
		t.Helper()
		if err := first.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, statement, args...); return err }); err != nil {
			t.Fatal(err)
		}
	}
	consume := func(t *testing.T, bucket *PostgresBucket, name string, capacity int, period time.Duration, want bool) {
		t.Helper()
		if allowed, err := bucket.Consume(ctx, name, capacity, period); err != nil || allowed != want {
			t.Fatalf("消费结果：allowed=%v want=%v err=%v", allowed, want, err)
		}
	}
	state := func(name string) (float64, time.Time) {
		t.Helper()
		var tokens float64
		var refilled time.Time
		if err := first.WithConn(ctx, func(c *sql.Conn) error {
			return c.QueryRowContext(ctx, "SELECT tokens,refilled_at FROM runtime_rate_bucket WHERE name=$1", name).Scan(&tokens, &refilled)
		}); err != nil {
			t.Fatal(err)
		}
		return tokens, refilled
	}
	t.Run("Java shared capacity and database clock refill", func(t *testing.T) {
		consume(t, one, "shared", 1, time.Minute, true)
		consume(t, two, "shared", 1, time.Minute, false)
		execute("UPDATE runtime_rate_bucket SET refilled_at=clock_timestamp()-interval '2 minutes' WHERE name='shared'")
		consume(t, two, "shared", 1, time.Minute, true)
	})
	t.Run("fractional rejection does not mutate state", func(t *testing.T) {
		execute("INSERT INTO runtime_rate_bucket VALUES('fraction',0.25,clock_timestamp())")
		before, stamp := state("fraction")
		consume(t, one, "fraction", 1, 24*time.Hour, false)
		after, nextStamp := state("fraction")
		if before != after || !stamp.Equal(nextStamp) {
			t.Fatal("拒绝时不能丢弃分数令牌或推进补充时间")
		}
	})
	t.Run("future clock and configuration changes", func(t *testing.T) {
		execute("INSERT INTO runtime_rate_bucket VALUES('future',1.5,clock_timestamp()+interval '1 hour')")
		consume(t, one, "future", 2, time.Hour, true)
		tokens, _ := state("future")
		if tokens != 0.5 {
			t.Fatal("数据库时钟回拨不能产生负补充")
		}
		execute("UPDATE runtime_rate_bucket SET tokens=10,refilled_at=clock_timestamp()+interval '1 hour' WHERE name='future'")
		consume(t, two, "future", 2, time.Hour, true)
		if tokens, _ := state("future"); tokens != 1 {
			t.Fatal("容量缩小应在消费时裁剪")
		}
		execute("UPDATE runtime_rate_bucket SET tokens=0,refilled_at=clock_timestamp()+interval '1 hour' WHERE name='future'")
		consume(t, two, "future", 7, time.Hour, false)
	})
	t.Run("independent transaction survives business rollback", func(t *testing.T) {
		business := errors.New("fixture business rollback")
		err := first.WithTx(ctx, sql.TxOptions{}, func(_ *sql.Tx) error {
			allowed, err := one.Consume(ctx, "independent", 1, 24*time.Hour)
			if err != nil || !allowed {
				return errors.New("独立消费未成功")
			}
			return business
		})
		if !errors.Is(err, business) {
			t.Fatal(err)
		}
		consume(t, two, "independent", 1, 24*time.Hour, false)
	})
	t.Run("concurrent initialization across pools has one budget", func(t *testing.T) {
		var accepted atomic.Int32
		var group sync.WaitGroup
		failures := make(chan error, 128)
		for i := range 128 {
			bucket := []*PostgresBucket{one, two}[i%2]
			group.Go(func() {
				allowed, err := bucket.Consume(ctx, "concurrent", 17, 24*time.Hour)
				if err != nil {
					failures <- err
				} else if allowed {
					accepted.Add(1)
				}
			})
		}
		group.Wait()
		close(failures)
		for err := range failures {
			t.Error(err)
		}
		if accepted.Load() != 17 || first.Stats().InUse != 0 || second.Stats().InUse != 0 {
			t.Fatalf("共享容量或连接释放错误，accepted=%d", accepted.Load())
		}
	})
	t.Run("separate OS processes share budget", func(t *testing.T) {
		execute("INSERT INTO runtime_rate_bucket VALUES('process-shared',13,clock_timestamp()+interval '1 hour')")
		type result struct {
			output []byte
			err    error
		}
		results := make(chan result, 2)
		for range 2 {
			go func() {
				command := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestPostgresBucketProcessHelper$", "-test.count=1")
				command.Env = append(os.Environ(), "GO_TEST_RATE_BUCKET_CHILD=1")
				out, err := command.CombinedOutput()
				results <- result{out, err}
			}()
		}
		total := 0
		for range 2 {
			result := <-results
			matches := regexp.MustCompile(`RATE_BUCKET_ALLOWED=(\d+)`).FindSubmatch(result.output)
			if result.err != nil || len(matches) != 2 {
				t.Errorf("限流子进程失败：%v %s", result.err, result.output)
				continue
			}
			count, err := strconv.Atoi(string(matches[1]))
			if err != nil {
				t.Error(err)
			}
			total += count
		}
		if total != 13 {
			t.Fatalf("两个真实进程共 64 次消费应只放行 13 次，得到 %d", total)
		}
	})
	t.Run("lock cancellation rolls back and releases connection", func(t *testing.T) {
		execute("INSERT INTO runtime_rate_bucket VALUES('locked',1,clock_timestamp()+interval '1 hour')")
		err := first.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, "SELECT name FROM runtime_rate_bucket WHERE name='locked' FOR UPDATE"); err != nil {
				return err
			}
			deadline, stop := context.WithTimeout(ctx, 100*time.Millisecond)
			defer stop()
			start := time.Now()
			allowed, err := two.Consume(deadline, "locked", 1, time.Hour)
			if allowed || !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 3*time.Second {
				return errors.New("锁等待必须响应期限并拒绝准入")
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
		if tokens, _ := state("locked"); tokens != 1 {
			t.Fatal("取消消费不能改变已有预算")
		}
		consume(t, two, "locked", 1, time.Hour, true)
		if second.Stats().InUse != 0 {
			t.Fatal("取消后不能泄漏连接")
		}
	})
	t.Run("missing schema fails without local fallback or auto creation", func(t *testing.T) {
		execute("ALTER TABLE runtime_rate_bucket RENAME TO fixture_saved_rate_bucket")
		defer execute("ALTER TABLE fixture_saved_rate_bucket RENAME TO runtime_rate_bucket")
		allowed, err := one.Consume(ctx, "secret-bucket-name", 1, time.Hour)
		var pgError *pgconn.PgError
		if allowed || !errors.As(err, &pgError) || pgError.Code != "42P01" || strings.Contains(fmt.Sprint(err), "runtime_rate_bucket") {
			t.Fatal("缺失表必须返回脱敏数据库错误，不建表/回退/放行")
		}
	})
}

// 仅由上面的父测试启动；每个进程持有独立数据库池，不使用父进程内存桶。
func TestPostgresBucketProcessHelper(t *testing.T) {
	if os.Getenv("GO_TEST_RATE_BUCKET_CHILD") != "1" {
		t.Skip("仅用于独立限流子进程")
	}
	bucket := fixtureBucket(t, fixtureBucketPool(t))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	accepted := 0
	for range 32 {
		allowed, err := bucket.Consume(ctx, "process-shared", 13, 24*time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		if allowed {
			accepted++
		}
	}
	fmt.Printf("RATE_BUCKET_ALLOWED=%d\n", accepted)
}
