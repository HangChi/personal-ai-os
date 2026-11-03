//go:build integration

package migration

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/database"
)

// pg_dump/restore可能将ANY数组整体的::text[]改为逐元素::text。
// 用同版本PostgreSQL在临时空表重解析两边CHECK/索引表达式，保留值/运算/类型，不能忽略条件。
func canonicalBackupExpressions(ctx context.Context, pool *database.Pool, snapshot schemaSnapshot) error {
	return pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		for i, item := range snapshot["constraints"] {
			var fields []json.RawMessage
			if err := json.Unmarshal([]byte(item.definition), &fields); err != nil {
				return err
			}
			if len(fields) != 5 || string(fields[0]) != `"c"` {
				continue
			}
			table, _, ok := strings.Cut(item.name, ".")
			if !ok || !validHistorySchema(table) {
				return errors.New("CHECK表名无效")
			}
			var definition string
			if err := json.Unmarshal(fields[4], &definition); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `CREATE TEMP TABLE go_check_canonical (LIKE public."`+table+`") ON COMMIT DROP; ALTER TABLE go_check_canonical ADD CONSTRAINT go_canonical_check `+definition); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, `SELECT pg_get_constraintdef(oid,true) FROM pg_constraint WHERE conrelid='pg_temp.go_check_canonical'::regclass AND conname='go_canonical_check'`).Scan(&definition); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DROP TABLE pg_temp.go_check_canonical`); err != nil {
				return err
			}
			fields[4], _ = json.Marshal(definition)
			encoded, err := json.Marshal(fields)
			if err != nil {
				return err
			}
			snapshot["constraints"][i].definition = string(encoded)
		}
		for i, item := range snapshot["indexes"] {
			var fields []json.RawMessage
			if err := json.Unmarshal([]byte(item.definition), &fields); err != nil {
				return err
			}
			if len(fields) != 5 {
				return errors.New("索引快照格式不受支持")
			}
			var definition string
			if err := json.Unmarshal(fields[4], &definition); err != nil {
				return err
			}
			prefix, using, ok := strings.Cut(definition, " USING ")
			_, table, hasTable := strings.Cut(prefix, " ON <schema>.")
			if !ok || !hasTable || !validHistorySchema(table) || !validHistorySchema(item.name) {
				return errors.New("索引定义不受支持")
			}
			if _, err := tx.ExecContext(ctx, `CREATE TEMP TABLE go_index_canonical (LIKE public."`+table+`") ON COMMIT DROP; `+strings.Replace(definition, " ON <schema>."+table+" ", " ON pg_temp.go_index_canonical ", 1)); err != nil {
				return err
			}
			var canonical string
			if err := tx.QueryRowContext(ctx, `SELECT pg_get_indexdef(oid) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname=$1`, item.name).Scan(&canonical); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DROP TABLE pg_temp.go_index_canonical`); err != nil {
				return err
			}
			_, using, ok = strings.Cut(canonical, " USING ")
			if !ok {
				return errors.New("规范化索引定义无效")
			}
			fields[4], _ = json.Marshal(prefix + " USING " + using)
			encoded, err := json.Marshal(fields)
			if err != nil {
				return err
			}
			snapshot["indexes"][i].definition = string(encoded)
		}
		return nil
	})
}

func snapshotBackupData(ctx context.Context, pool *database.Pool) (map[string]string, error) {
	values := map[string]string{}
	err := pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename`)
		if err != nil {
			return err
		}
		var tables []string
		for rows.Next() {
			var name string
			if err := rows.Scan(&name); err != nil {
				rows.Close()
				return err
			}
			tables = append(tables, name)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		for _, name := range tables {
			if !validHistorySchema(name) {
				return errors.New("备份表标识无效")
			}
			var digest string
			// 原样比较全部行/全部列，排序仅消除物理扫描顺序；不忽略时间、空值或正文。
			if err := tx.QueryRowContext(ctx, `SELECT encode(digest(COALESCE(string_agg(row_to_json(t)::text,E'\n' ORDER BY row_to_json(t)::text),''),'sha256'),'hex') FROM public."`+name+`" t`).Scan(&digest); err != nil {
				return err
			}
			values["table:"+name] = digest
		}
		rows, err = tx.QueryContext(ctx, `SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename`)
		if err != nil {
			return err
		}
		var sequences []string
		for rows.Next() {
			var name string
			if err := rows.Scan(&name); err != nil {
				rows.Close()
				return err
			}
			sequences = append(sequences, name)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		for _, name := range sequences {
			if !validHistorySchema(name) {
				return errors.New("备份序列标识无效")
			}
			var state string
			if err := tx.QueryRowContext(ctx, `SELECT last_value::text||':'||is_called::text FROM public."`+name+`"`).Scan(&state); err != nil {
				return err
			}
			values["sequence:"+name] = state
		}
		return nil
	})
	return values, err
}

func TestRealPostgresBackupRestoreSyntheticDataAndWrites(t *testing.T) {
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_BACKUP") != "disposable" {
		t.Skip("需--backup的合成数据专用恢复库")
	}
	source, restored := historyDatabase(t, false), historyDatabase(t, true)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	scripts, err := LoadVersioned(os.DirFS(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration")))
	if err != nil {
		t.Fatal(err)
	}
	validator, err := NewHistoryValidator(restored, scripts, "public")
	if err != nil {
		t.Fatal(err)
	}
	if result, err := validator.Validate(ctx); err != nil || result.Applied != 42 {
		t.Fatal("恢复后的Flyway历史不兼容", err)
	}
	java, err := captureSchema(ctx, source, "public")
	if err != nil {
		t.Fatal(err)
	}
	restoredSchema, err := captureSchema(ctx, restored, "public")
	if err != nil {
		t.Fatal(err)
	}
	if err := canonicalBackupExpressions(ctx, source, java); err != nil {
		t.Fatal(err)
	}
	if err := canonicalBackupExpressions(ctx, restored, restoredSchema); err != nil {
		t.Fatal(err)
	}
	if err := compareSchema(java, restoredSchema); err != nil {
		t.Fatal("备份恢复DDL不一致", err)
	}
	for _, probe := range []struct{ category, from, to string }{{"constraints", "'QUEUE'", "'DIFFERENT'"}, {"indexes", "'PENDING'", "'DIFFERENT'"}} {
		altered := schemaSnapshot{}
		for category, rows := range restoredSchema {
			altered[category] = append([]schemaObject(nil), rows...)
		}
		changed := false
		for i, row := range altered[probe.category] {
			if strings.Contains(row.definition, probe.from) {
				altered[probe.category][i].definition = strings.Replace(row.definition, probe.from, probe.to, 1)
				changed = true
				break
			}
		}
		if !changed {
			t.Fatal("未找到预期的CHECK/部分索引条件反例")
		}
		if err := canonicalBackupExpressions(ctx, restored, altered); err != nil {
			t.Fatal(err)
		}
		if err := compareSchema(java, altered); err == nil {
			t.Fatal("表达式规范化掩盖了实际条件值差异", probe.category)
		}
	}
	want, err := snapshotBackupData(ctx, source)
	if err != nil {
		t.Fatal(err)
	}
	got, err := snapshotBackupData(ctx, restored)
	if err != nil {
		t.Fatal(err)
	}
	if len(want) != 50 || len(got) != len(want) {
		t.Fatal("应逐项比较40张含历史表和10序列", len(want), len(got))
	}
	for key, value := range want {
		if got[key] != value {
			t.Fatalf("备份恢复后行或序列状态改变: %s", key)
		}
	}
	err = restored.WithConn(ctx, func(conn *sql.Conn) error {
		var title, profile string
		var due time.Time
		if err := conn.QueryRowContext(ctx, `SELECT title,due_at FROM task WHERE id='00000000-0000-0000-0000-000000000042'`).Scan(&title, &due); err != nil {
			return err
		}
		if title != "恢复任务😀" || !due.Equal(time.Date(2026, 11, 3, 0, 30, 0, 0, time.UTC)) {
			return errors.New("恢复后Unicode或时区不一致")
		}
		var payload []byte
		if err := conn.QueryRowContext(ctx, `SELECT payload FROM knowledge_index_job WHERE id='00000000-0000-0000-0000-000000000045'`).Scan(&payload); err != nil {
			return err
		}
		binary, _ := hex.DecodeString("000102ff00e4b8ad")
		if !bytes.Equal(payload, binary) {
			return errors.New("恢复后bytea/NUL数据不一致")
		}
		var dimensions int
		var distance float64
		if err := conn.QueryRowContext(ctx, `SELECT vector_dims(embedding),embedding<=>embedding FROM memory WHERE id='00000000-0000-0000-0000-000000000043'`).Scan(&dimensions, &distance); err != nil {
			return err
		}
		if dimensions != 1024 || distance != 0 {
			return errors.New("恢复后pgvector不可查询")
		}
		if err := conn.QueryRowContext(ctx, `SELECT api_key FROM ai_model_profile WHERE id='00000000-0000-0000-0000-000000000044'`).Scan(&profile); err != nil {
			return err
		}
		if profile != "fixture-opaque-value-not-a-real-key" {
			return errors.New("虚构opaque值未保留")
		}
		var before, id int64
		if err := conn.QueryRowContext(ctx, `SELECT max(id) FROM chat_message`).Scan(&before); err != nil {
			return err
		}
		if err := conn.QueryRowContext(ctx, `INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES('backup-fixture','ASSISTANT','恢复后可继续写',2) RETURNING id`).Scan(&id); err != nil {
			return err
		}
		if id <= before {
			return errors.New("恢复后BIGSERIAL未从原值继续")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	err = restored.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES('backup-fixture','USER','duplicate',1)`)
		return err
	})
	var pgError *pgconn.PgError
	if !errors.As(err, &pgError) || pgError.Code != "23505" {
		t.Fatal("恢复后唯一约束未保留", err)
	}
	after, err := snapshotBackupData(ctx, source)
	if err != nil {
		t.Fatal(err)
	}
	keys := make([]string, 0, len(want))
	for key := range want {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	for _, key := range keys {
		if after[key] != want[key] {
			t.Fatal("恢复库写入影响源库", strings.TrimPrefix(key, "table:"))
		}
	}
	t.Log("恢复后40表/10序列数据一致，1024维向量/bytea/Unicode/时区/opaque值及继续写入/唯一约束通过")
}
