//go:build integration

package migration

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/database"
)

type schemaObject struct{ name, definition string }
type schemaSnapshot map[string][]schemaObject

// 仅测试中的pg_catalog定义差分；不是可绕过完整schema验收的生产布尔占位。
func captureSchema(ctx context.Context, pool *database.Pool, schema string) (schemaSnapshot, error) {
	if !validHistorySchema(schema) {
		return nil, errors.New("测试schema无效")
	}
	queries := map[string]string{
		"tables": `SELECT c.relname,json_build_array(c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,c.relreplident,c.reloptions,pg_get_partkeydef(c.oid))::text
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 AND c.relkind IN ('r','p') AND c.relname<>'flyway_schema_history' ORDER BY 1`,
		"columns": `SELECT c.relname||'.'||a.attname,json_build_array(a.attnum,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,pg_get_expr(d.adbin,d.adrelid),co.collname)::text
FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum LEFT JOIN pg_collation co ON co.oid=a.attcollation
WHERE n.nspname=$1 AND c.relkind IN ('r','p') AND c.relname<>'flyway_schema_history' AND a.attnum>0 AND NOT a.attisdropped ORDER BY 1`,
		"constraints": `SELECT c.relname||'.'||k.conname,json_build_array(k.contype,k.condeferrable,k.condeferred,k.convalidated,pg_get_constraintdef(k.oid,true))::text
FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname=$1 AND c.relname<>'flyway_schema_history' ORDER BY 1`,
		"indexes": `SELECT i.relname,json_build_array(x.indisunique,x.indisprimary,x.indisvalid,x.indisready,pg_get_indexdef(x.indexrelid))::text
FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname=$1 AND c.relname<>'flyway_schema_history' ORDER BY 1`,
		"sequences": `SELECT c.relname,json_build_array(format_type(s.seqtypid,-1),s.seqstart,s.seqincrement,s.seqmax,s.seqmin,s.seqcache,s.seqcycle)::text
FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 ORDER BY 1`,
		"triggers": `SELECT c.relname||'.'||t.tgname,json_build_array(t.tgenabled,pg_get_triggerdef(t.oid,true))::text
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 AND NOT t.tgisinternal ORDER BY 1`,
		"functions": `SELECT p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=$1 AND p.prokind IN ('f','p')
AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e') ORDER BY 1`,
	}
	snapshot := schemaSnapshot{}
	err := pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		// 每份结构用自身search_path显示可见对象，避免public与测试schema的冗余限定符差异。
		if _, err := tx.ExecContext(ctx, `SET LOCAL search_path="`+schema+`",public`); err != nil {
			return err
		}
		for category, query := range queries {
			rows, err := tx.QueryContext(ctx, query, schema)
			if err != nil {
				return err
			}
			values := []schemaObject{}
			for rows.Next() {
				var item schemaObject
				if err := rows.Scan(&item.name, &item.definition); err != nil {
					rows.Close()
					return err
				}
				// 只归一化本份结构的限定符，不忽略类型/默认值/约束/函数体差异。
				item.definition = strings.ReplaceAll(item.definition, `"`+schema+`".`, "<schema>.")
				item.definition = strings.ReplaceAll(item.definition, schema+".", "<schema>.")
				values = append(values, item)
			}
			err = rows.Err()
			rows.Close()
			if err != nil {
				return err
			}
			snapshot[category] = values
		}
		return nil
	})
	return snapshot, err
}

func compareSchema(expected, actual schemaSnapshot) error {
	if len(expected) != len(actual) {
		return errors.New("结构分类数量不一致")
	}
	for category, want := range expected {
		got, ok := actual[category]
		if !ok || len(want) != len(got) {
			return fmt.Errorf("%s 对象数量不一致: Java=%d Go=%d", category, len(want), len(got))
		}
		if !slices.Equal(want, got) {
			for i, row := range want {
				if row != got[i] {
					return fmt.Errorf("%s 定义不一致: %s; expected=%s actual=%s", category, row.name, row.definition, got[i].definition)
				}
			}
		}
	}
	return nil
}

func assertSchemaMatchesJavaFlyway(t *testing.T, pool *database.Pool, ctx context.Context, schema string) {
	t.Helper()
	java, err := captureSchema(ctx, pool, "public")
	if err != nil {
		t.Fatal(err)
	}
	goReplay, err := captureSchema(ctx, pool, schema)
	if err != nil {
		t.Fatal(err)
	}
	if err := compareSchema(java, goReplay); err != nil {
		t.Fatal(err)
	}
	if len(java["tables"]) != 48 || len(java["triggers"]) != 21 || len(java["columns"]) == 0 || len(java["functions"]) == 0 {
		t.Fatal("Java真实结构未覆盖完整对象")
	}
	// 用真实DDL破坏副本，确认比较器能发现差异；绝不修改Java迁移的public。
	if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `ALTER TABLE "`+schema+`".task ADD COLUMN go_schema_difference_probe integer`)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			_, err := conn.ExecContext(ctx, `ALTER TABLE "`+schema+`".task DROP COLUMN go_schema_difference_probe`)
			return err
		}); err != nil {
			t.Error(err)
		}
	}()
	changed, err := captureSchema(ctx, pool, schema)
	if err != nil {
		t.Fatal(err)
	}
	if err := compareSchema(java, changed); err == nil {
		t.Fatal("完整结构比较没有发现实际额外列")
	}
	t.Logf("Java/Go完整定义一致：%d表、%d列、%d约束、%d索引、%d序列、%d触发器、%d函数", len(java["tables"]), len(java["columns"]), len(java["constraints"]), len(java["indexes"]), len(java["sequences"]), len(java["triggers"]), len(java["functions"]))
}
