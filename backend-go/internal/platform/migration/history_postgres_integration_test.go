//go:build integration

package migration

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/database"
)

func historyFixture(t *testing.T) *database.Pool {
	return historyDatabase(t, false)
}

func historyDatabase(t *testing.T, restored bool) *database.Pool {
	t.Helper()
	settings := database.DefaultPostgresOptions()
	settings.Host, settings.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	settings.Username, settings.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || err != nil || settings.Host != "127.0.0.1" || settings.Database != "personalos_go_test" || settings.Username != "go_fixture" {
		t.Fatal("不是专用临时迁移库")
	}
	settings.Port = port
	if restored {
		if os.Getenv("GO_TEST_BACKUP") != "disposable" {
			t.Fatal("恢复库没有显式临时测试标记")
		}
		settings.Database = "personalos_go_restore"
	}
	settings.SSLMode, settings.RootCert = os.Getenv("GO_TEST_POSTGRES_SSL_MODE"), os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	if settings.SSLMode == "" {
		settings.SSLMode = "disable"
	}
	connector, err := database.NewPostgresConnector(settings)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(true))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pool.Close() })
	return pool
}

func TestRealFlywayHistoryReadOnlyValidation(t *testing.T) {
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需 --flyway 的真实Java迁移临时集群")
	}
	pool := historyFixture(t)
	scripts, err := LoadVersioned(os.DirFS(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration")))
	if err != nil {
		t.Fatal(err)
	}
	validator, err := NewHistoryValidator(pool, scripts, "public")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	historyDigest := func() string {
		t.Helper()
		var digest string
		if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			return conn.QueryRowContext(ctx, `SELECT md5(string_agg(row_to_json(h)::text,'' ORDER BY installed_rank)) FROM public.flyway_schema_history h`).Scan(&digest)
		}); err != nil {
			t.Fatal(err)
		}
		return digest
	}
	before := historyDigest()
	status, err := validator.Validate(ctx)
	if err != nil || status.Applied != 42 || status.CurrentVersion != 42 {
		t.Fatal("真实Flyway历史不兼容", err)
	}
	// 关闭/清空调用方目录切片不得更改已经构造的预期值。
	scripts[0].SQL, scripts[0].Name = "tampered", "other"
	if _, err := validator.Validate(ctx); err != nil {
		t.Fatal("保存了调用方可变目录", err)
	}
	if before != historyDigest() {
		t.Fatal("Go校验修改了真实Flyway历史")
	}
	// 所有破坏性场景仅在历史表副本；不修改Java真正生成的历史。
	err = pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `CREATE SCHEMA go_history_probe; CREATE TABLE go_history_probe.flyway_schema_history (LIKE public.flyway_schema_history INCLUDING ALL)`)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = pool.WithConn(cleanup, func(conn *sql.Conn) error {
			_, err := conn.ExecContext(cleanup, "DROP SCHEMA go_history_probe CASCADE")
			return err
		})
	})
	clone := &HistoryValidator{pool: pool, schema: "go_history_probe", expected: validator.expected}
	for name, statement := range map[string]string{
		"missing":       "DELETE FROM go_history_probe.flyway_schema_history WHERE version='42'",
		"checksum":      "UPDATE go_history_probe.flyway_schema_history SET checksum=0 WHERE version='17'",
		"null checksum": "UPDATE go_history_probe.flyway_schema_history SET checksum=NULL WHERE version='17'",
		"failed":        "UPDATE go_history_probe.flyway_schema_history SET success=false WHERE version='17'",
		"version":       "UPDATE go_history_probe.flyway_schema_history SET version='99' WHERE version='17'",
		"script":        "UPDATE go_history_probe.flyway_schema_history SET script='unexpected-sensitive-text' WHERE version='17'",
		"description":   "UPDATE go_history_probe.flyway_schema_history SET description='unexpected-sensitive-text' WHERE version='17'",
		"baseline":      "UPDATE go_history_probe.flyway_schema_history SET type='BASELINE',checksum=NULL WHERE version='1'",
		"future":        "INSERT INTO go_history_probe.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(43,'43','future','SQL','V43__future.sql',0,'fixture',0,true)",
	} {
		t.Run(name, func(t *testing.T) {
			if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
				_, err := conn.ExecContext(ctx, "TRUNCATE go_history_probe.flyway_schema_history; INSERT INTO go_history_probe.flyway_schema_history SELECT * FROM public.flyway_schema_history; "+statement)
				return err
			}); err != nil {
				t.Fatal(err)
			}
			if result, err := clone.Validate(ctx); !errors.Is(err, ErrInvalidHistory) || result.Applied != 0 {
				t.Fatal("损坏历史被接受", err)
			} else if strings.Contains(fmt.Sprintf("%v %#v", err, err), "unexpected-sensitive-text") {
				t.Fatal("泄漏了未受信任的历史字段")
			}
		})
	}
	missing := &HistoryValidator{pool: pool, schema: "go_no_history", expected: validator.expected}
	_, err = missing.Validate(ctx)
	var pgError *pgconn.PgError
	if !errors.Is(err, ErrInvalidHistory) || !errors.As(err, &pgError) || pgError.Code != "42P01" {
		t.Fatal("缺历史表被伪造/忽略", err)
	}
	cancelled, stop := context.WithCancel(ctx)
	stop()
	if _, err := validator.Validate(cancelled); !errors.Is(err, context.Canceled) {
		t.Fatal("未传播取消", err)
	}
	if before != historyDigest() {
		t.Fatal("失败校验修改了原历史")
	}
}
