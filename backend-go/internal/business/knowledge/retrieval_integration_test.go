//go:build integration

package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"
)

func TestP6HybridThresholdIDFChineseAndLiteralPattern(t *testing.T) {
	s := fixtureStore(t)
	s.Reader.Properties.HybridSearchEnabled = true
	s.Reader.Properties.SimilarityThreshold = .9
	ctx := context.Background()
	a, e := s.Ingest(ctx, "2pc.txt", "", []byte("2PC provides atomic commit protocol with consensus."))
	if e != nil {
		t.Fatal(e)
	}
	_, e = s.Ingest(ctx, "other.txt", "", []byte("An unrelated PostgreSQL description."))
	if e != nil {
		t.Fatal(e)
	}
	execSQL(t, s.Pool, "UPDATE vector_store SET embedding=('[-1,'||repeat('0,',1022)||'0]')::vector WHERE metadata->>'document_id'=$1", a.ID)
	r := &Retriever{Store: s}
	hits, e := r.Search(ctx, "2PC 是什么", nil)
	if e != nil || len(hits) != 2 {
		t.Fatal(hits, e)
	}
	found := false
	for _, h := range hits {
		if h.DocumentID == a.ID {
			found = true
			if h.MatchedBy != "KEYWORD" || h.Score != nil {
				t.Fatal(h)
			}
		}
	}
	if !found {
		t.Fatal(hits)
	}
	s.Reader.Properties.HybridSearchEnabled = false
	hits, e = r.Search(ctx, "2PC 是什么", nil)
	if e != nil || len(hits) != 1 || hits[0].DocumentID == a.ID {
		t.Fatal(hits, e)
	}
	s.Reader.Properties.HybridSearchEnabled = true
	chinese, e := s.Ingest(ctx, "中文.txt", "", []byte("索引失效时需要检查数据库执行计划与统计信息。"))
	if e != nil {
		t.Fatal(e)
	}
	execSQL(t, s.Pool, "UPDATE vector_store SET embedding=('[-1,'||repeat('0,',1022)||'0]')::vector WHERE metadata->>'document_id'=$1", chinese.ID)
	hits, e = r.Candidates(ctx, "索引失效怎么办", 40)
	if e != nil {
		t.Fatal(e)
	}
	found = false
	for _, h := range hits {
		if h.DocumentID == chinese.ID {
			found = true
		}
	}
	if !found {
		t.Fatal("中文候选召回丢失", hits)
	}
	literal, e := s.Ingest(ctx, "literal.txt", "", []byte("literal ip_hash 100% a\\b special characters"))
	if e != nil {
		t.Fatal(e)
	}
	for _, token := range []string{"100%", "ip_hash", "a\\b"} {
		matches, e := s.KeywordMatches(ctx, []string{token}, 20)
		if e != nil || len(matches) != 1 || matches[0].Chunk.Metadata["document_id"] != literal.ID {
			t.Fatal(token, matches, e)
		}
	}
	if scalar[string](t, s.Pool, "SELECT am.amname FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am am ON am.oid=c.relam WHERE c.relname='spring_ai_vector_index'") != "hnsw" {
		t.Fatal("未使用HNSW")
	}
}

type externalFixture struct {
	ids  []string
	fail bool
}

func (e externalFixture) Search(context.Context, string, []float32, int) ([]string, []string, error) {
	if e.fail {
		return nil, nil, errors.New("offline")
	}
	return e.ids, e.ids, nil
}
func TestP6ExternalIDsHydrateAuthoritativeRowsAndFallback(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	d, e := s.Ingest(ctx, "real.txt", "", []byte("Authoritative PostgreSQL content."))
	if e != nil {
		t.Fatal(e)
	}
	id := scalar[string](t, s.Pool, "SELECT id::text FROM vector_store WHERE metadata->>'document_id'=$1 LIMIT 1", d.ID)
	execSQL(t, s.Pool, "DELETE FROM knowledge_search_outbox")
	r := &Retriever{Store: s, External: externalFixture{ids: []string{id, newID()}}}
	hits, e := r.Candidates(ctx, "content", 20)
	if e != nil || len(hits) != 1 || hits[0].Content != "Authoritative PostgreSQL content." || hits[0].MatchedBy != "BOTH" || hits[0].Score != nil {
		t.Fatal(hits, e)
	}
	r.External = externalFixture{fail: true}
	hits, e = r.Candidates(ctx, "content", 20)
	if e != nil || len(hits) != 1 || hits[0].MatchedBy != "VECTOR" {
		t.Fatal(hits, e)
	}
	_, e = s.Delete(ctx, d.ID)
	if e != nil {
		t.Fatal(e)
	}
	rows, e := s.ProjectionChunks(ctx, []string{id})
	if e != nil || len(rows) != 0 {
		t.Fatal(rows, e)
	}
}
func TestP6KeywordWindowCountsBeforeLimitAndRRF(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	s.Reader.Properties.HybridSearchEnabled = true
	for i := 0; i < 8; i++ {
		_, e := s.Ingest(ctx, newID()+".txt", "", []byte(strings.Repeat("common_token ", i+1)+"data"))
		if e != nil {
			t.Fatal(e)
		}
	}
	m, e := s.KeywordMatches(ctx, []string{"common_token"}, 2)
	if e != nil || len(m) != 2 || m[0].MatchedCount != 8 || m[0].TotalChunks != 8 {
		t.Fatal(m, e)
	}
	if len(m[0].Chunk.Content) > len(m[1].Chunk.Content) {
		t.Fatal(m)
	}
	r := &Retriever{Store: s}
	hits, e := r.Candidates(ctx, "common_token", 20)
	if e != nil || len(hits) != 8 {
		t.Fatal(hits, e)
	}
	for _, h := range hits {
		if h.Score == nil || *h.Score < .99 || h.MatchedBy != "BOTH" {
			t.Fatal(h)
		}
	}
}

var _ *sql.Conn
