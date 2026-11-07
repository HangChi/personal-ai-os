//go:build integration

package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"
)

func TestP6IndependentUploadsAndExpressionIndexPlan(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	first, e := s.Ingest(ctx, "same.txt", "", []byte("重复上传是两个独立文档，不按内容合并。"))
	if e != nil {
		t.Fatal(e)
	}
	second, e := s.Ingest(ctx, "same.txt", "", []byte("重复上传是两个独立文档，不按内容合并。"))
	if e != nil || first.ID == second.ID {
		t.Fatal(first, second, e)
	}
	if _, e = s.Delete(ctx, first.ID); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM vector_store WHERE metadata->>'document_id'=$1", second.ID) != second.ChunkCount {
		t.Fatal("deleting one duplicate removed the other document")
	}
	if scalar[string](t, s.Pool, "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='vector_store' AND column_name='metadata'") != "json" {
		t.Fatal("original metadata column type changed")
	}
	rollback := errors.New("rollback schema test")
	e = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, `INSERT INTO vector_store(id,content,metadata,embedding) SELECT gen_random_uuid(),'chunk '||i,json_build_object('document_id',CASE WHEN i%100=0 THEN 'doc-target' ELSE 'doc-'||(i%100) END,'chunk_index',i),array_fill(0.0,ARRAY[1024])::vector FROM generate_series(1,1000) i`); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, "ANALYZE vector_store"); e != nil {
			return e
		}
		plan := func() (string, error) {
			rows, e := tx.QueryContext(ctx, "EXPLAIN (COSTS OFF) SELECT count(*) FROM vector_store WHERE metadata->>'document_id'='doc-target'")
			if e != nil {
				return "", e
			}
			defer rows.Close()
			var lines []string
			for rows.Next() {
				var line string
				if e := rows.Scan(&line); e != nil {
					return "", e
				}
				lines = append(lines, line)
			}
			return strings.Join(lines, "\n"), rows.Err()
		}
		with, e := plan()
		if e != nil {
			return e
		}
		if !strings.Contains(with, "idx_vector_store_document_id") {
			t.Error("expression index is unused:", with)
		}
		if _, e := tx.ExecContext(ctx, "DROP INDEX idx_vector_store_document_id"); e != nil {
			return e
		}
		without, e := plan()
		if e != nil {
			return e
		}
		if strings.Contains(without, "idx_vector_store_document_id") || !strings.Contains(without, "Seq Scan") {
			t.Error("missing-index counterexample failed:", without)
		}
		return rollback
	})
	if !errors.Is(e, rollback) {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='idx_vector_store_document_id'") != 1 {
		t.Fatal("schema test did not restore the original index")
	}
}
