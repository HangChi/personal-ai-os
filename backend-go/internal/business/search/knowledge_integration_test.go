//go:build integration

package search

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"testing"

	"personal-ai-os/backend-go/internal/platform/testdb"
)

var errSearchFixtureRollback = errors.New("回滚搜索测试夹具")

func TestKnowledgeBodyBoundedSupplement(t *testing.T) {
	pool := testdb.Open(t)
	ctx := context.Background()
	err := pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		// 使用回滚事务隔离夹具，不接触其它用例的数据。
		if _, err := tx.ExecContext(ctx, "TRUNCATE knowledge_document, vector_store CASCADE"); err != nil {
			return err
		}
		for i := 1; i <= 8; i++ {
			id := fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
			title := "ordinary"
			if i == 1 {
				title = "needle metadata"
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count) VALUES($1::uuid,$2,'fixture.md','text/plain',1,1,2)`, id, title); err != nil {
				return err
			}
			for j := 0; j < 2; j++ {
				if _, err := tx.ExecContext(ctx, `INSERT INTO vector_store(content,metadata) VALUES('needle',json_build_object('document_id',$1::text))`, id); err != nil {
					return err
				}
			}
		}
		for _, id := range []string{"not-uuid", "00000000-0000-0000-0000-000000000099", ""} {
			if _, err := tx.ExecContext(ctx, `INSERT INTO vector_store(content,metadata) VALUES('needle',json_build_object('document_id',$1::text))`, id); err != nil {
				return err
			}
		}
		hits, err := knowledgeQuery(ctx, tx, "public.vector_store", "needle", 3)
		if err != nil {
			return err
		}
		if len(hits) != 3 || hits[0].MatchedField != "标题" || hits[1].ID != "00000000-0000-0000-0000-000000000002" || hits[2].ID != "00000000-0000-0000-0000-000000000003" {
			t.Fatalf("hits=%+v", hits)
		}
		// 返回专用错误，让WithTx回滚全部夹具。
		return errSearchFixtureRollback
	})
	if err != nil && !errors.Is(err, errSearchFixtureRollback) {
		t.Fatal(err)
	}
}

func TestKnowledgeBodyLiteralWildcards(t *testing.T) {
	pool := testdb.Open(t)
	ctx := context.Background()
	err := pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "TRUNCATE knowledge_document, vector_store CASCADE"); err != nil {
			return err
		}
		for i, content := range []string{"100%", "a_b", `c:\tmp`, "ordinary"} {
			id := fmt.Sprintf("00000000-0000-0000-0000-%012d", i+1)
			if _, err := tx.ExecContext(ctx, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count) VALUES($1::uuid,'ordinary','fixture.md','text/plain',1,1)`, id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO vector_store(content,metadata) VALUES($1,json_build_object('document_id',$2::text))`, content, id); err != nil {
				return err
			}
		}
		for i, keyword := range []string{"%", "_", `\`} {
			hits, err := knowledgeQuery(ctx, tx, "public.vector_store", keyword, 5)
			if err != nil {
				return err
			}
			if len(hits) != 1 || hits[0].ID != fmt.Sprintf("00000000-0000-0000-0000-%012d", i+1) {
				t.Fatalf("keyword=%q hits=%+v", keyword, hits)
			}
		}
		return errSearchFixtureRollback
	})
	if err != nil && !errors.Is(err, errSearchFixtureRollback) {
		t.Fatal(err)
	}
}
