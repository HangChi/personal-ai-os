//go:build integration

package migration

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRealFlywayBaselineV1ReadOnlyHistorySchemaAndOldData(t *testing.T) {
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY_BASELINE") != "disposable" {
		t.Skip("需--baseline的新建旧库夹具")
	}
	pool := historyFixture(t)
	scripts, err := LoadVersioned(os.DirFS(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration")))
	if err != nil {
		t.Fatal(err)
	}
	strict, err := NewHistoryValidator(pool, scripts, "go_flyway_baseline")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := strict.Validate(ctx); !errors.Is(err, ErrInvalidHistory) {
		t.Fatal("默认策略不能意外放开baseline", err)
	}
	validator, err := NewHistoryValidatorWithBaselineV1(pool, scripts, "go_flyway_baseline")
	if err != nil {
		t.Fatal(err)
	}
	digest := func() string {
		t.Helper()
		var value string
		if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			return conn.QueryRowContext(ctx, `SELECT md5(string_agg(row_to_json(h)::text,'' ORDER BY installed_rank)) FROM go_flyway_baseline.flyway_schema_history h`).Scan(&value)
		}); err != nil {
			t.Fatal(err)
		}
		return value
	}
	before := digest()
	status, err := validator.Validate(ctx)
	if err != nil || status.Applied != 41 || status.CurrentVersion != 42 || status.BaselineVersion != 1 {
		t.Fatal("真实baseline历史未对齐", status, err)
	}
	normal, err := captureSchema(ctx, pool, "public")
	if err != nil {
		t.Fatal(err)
	}
	baselined, err := captureSchema(ctx, pool, "go_flyway_baseline")
	if err != nil {
		t.Fatal(err)
	}
	if err := compareSchema(normal, baselined); err != nil {
		t.Fatal("真实baseline后的七类DDL不等价", err)
	}
	if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		var title, content string
		if err := conn.QueryRowContext(ctx, `SELECT s.title,m.content FROM go_flyway_baseline.chat_session s JOIN go_flyway_baseline.chat_message m USING(conversation_id) WHERE s.conversation_id='baseline-fixture' AND m.sequence_no=1`).Scan(&title, &content); err != nil {
			return err
		}
		if title != "基线前会话😀" || content != "基线前消息" {
			return errors.New("baseline破坏了旧数据")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if before != digest() {
		t.Fatal("Go只读校验修改了baseline历史")
	}
	// 损坏测试只改副本；真实Java生成的baseline保持不动。
	if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `CREATE SCHEMA go_baseline_probe; CREATE TABLE go_baseline_probe.flyway_schema_history (LIKE go_flyway_baseline.flyway_schema_history INCLUDING ALL)`)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = pool.WithConn(cleanup, func(conn *sql.Conn) error {
			_, err := conn.ExecContext(cleanup, `DROP SCHEMA go_baseline_probe CASCADE`)
			return err
		})
	})
	probe, err := NewHistoryValidatorWithBaselineV1(pool, scripts, "go_baseline_probe")
	if err != nil {
		t.Fatal(err)
	}
	for name, change := range map[string]string{
		"custom marker":   `UPDATE go_baseline_probe.flyway_schema_history SET description='fixture-private-marker' WHERE type='BASELINE'`,
		"marker checksum": `UPDATE go_baseline_probe.flyway_schema_history SET checksum=0 WHERE type='BASELINE'`,
		"baseline 2":      `UPDATE go_baseline_probe.flyway_schema_history SET version='2' WHERE type='BASELINE'`,
		"SQL checksum":    `UPDATE go_baseline_probe.flyway_schema_history SET checksum=0 WHERE version='42'`,
		"future":          `INSERT INTO go_baseline_probe.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(43,'43','future','SQL','V43__future.sql',0,'fixture',0,true)`,
	} {
		t.Run(name, func(t *testing.T) {
			if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
				_, err := conn.ExecContext(ctx, `TRUNCATE go_baseline_probe.flyway_schema_history; INSERT INTO go_baseline_probe.flyway_schema_history SELECT * FROM go_flyway_baseline.flyway_schema_history; `+change)
				return err
			}); err != nil {
				t.Fatal(err)
			}
			if status, err := probe.Validate(ctx); !errors.Is(err, ErrInvalidHistory) || status.Applied != 0 {
				t.Fatal("损坏基线被接受", status, err)
			} else if strings.Contains(err.Error(), "fixture-private") {
				t.Fatal("历史文本泄露")
			}
		})
	}
	if before != digest() {
		t.Fatal("失败校验修改了原baseline")
	}
	t.Log("真实baseline1/41 SQL历史只读通过，七类DDL等价且原聊天数据保留")
}
