//go:build integration

package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需要原迁移创建的独立测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, e := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if e != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许临时独立测试库")
	}
	opts.Port = port
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, e := database.NewPostgresConnector(opts)
	if e != nil {
		t.Fatal(e)
	}
	pool, e := database.Open(context.Background(), connector, database.DefaultPoolOptions(false))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		if e := pool.Close(); e != nil {
			t.Error(e)
		}
	})
	return pool
}
func execSQL(t *testing.T, pool *database.Pool, query string, args ...any) {
	t.Helper()
	if e := pool.WithConn(context.Background(), func(c *sql.Conn) error { _, e := c.ExecContext(context.Background(), query, args...); return e }); e != nil {
		t.Fatal(e)
	}
}
func scalar[T any](t *testing.T, pool *database.Pool, query string, args ...any) T {
	t.Helper()
	var value T
	if e := pool.WithConn(context.Background(), func(c *sql.Conn) error { return c.QueryRowContext(context.Background(), query, args...).Scan(&value) }); e != nil {
		t.Fatal(e)
	}
	return value
}

type embeddingFunc func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error)

func (f embeddingFunc) Embed(ctx context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	return f(ctx, r)
}
func fixtureEmbedding(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	v := model.EmbeddingResponse{}
	for i := range r.Input {
		vec := make([]float32, 1024)
		vec[0] = 1
		v.Results = append(v.Results, model.EmbeddingResult{Index: i, Vector: vec})
	}
	return v, nil
}
func fixtureStore(t *testing.T) *Store {
	t.Helper()
	pool := fixturePool(t)
	execSQL(t, pool, "DELETE FROM knowledge_index_job; DELETE FROM knowledge_document; DELETE FROM vector_store; DELETE FROM runtime_lease WHERE resource LIKE 'document:%'")
	locks, e := coordination.New(pool)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = locks.Close(context.Background()) })
	s, e := NewStore(context.Background(), pool, Reader{Properties: testProperties(t)}, embeddingFunc(fixtureEmbedding), locks, "public", "vector_store")
	if e != nil {
		t.Fatal(e)
	}
	return s
}
func TestP6DocumentAtomicReplaceMetadataDeleteAndPagination(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	d, e := s.Ingest(ctx, "../old.md", "custom", []byte(strings.Repeat("PostgreSQL transaction isolation. ", 80)))
	if e != nil {
		t.Fatal(e)
	}
	if d.ChunkCount < 2 {
		t.Fatal(d)
	}
	if count := scalar[int](t, s.Pool, "SELECT count(*) FROM vector_store WHERE metadata->>'document_id'=$1", d.ID); count != d.ChunkCount {
		t.Fatal(count, d)
	}
	original := d
	s.Embedding = embeddingFunc(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		return model.EmbeddingResponse{}, errors.New("secret upstream")
	})
	_, e = s.Update(ctx, d.ID, "new.md", []byte(strings.Repeat("replacement ", 80)))
	expectBusiness(t, e, api.AIUpstreamError, "嵌入")
	d, e = s.Document(ctx, d.ID)
	if e != nil || d != original {
		t.Fatal(d, e)
	}
	s.Embedding = embeddingFunc(fixtureEmbedding)
	d, e = s.Update(ctx, d.ID, "new100%.md", []byte(strings.Repeat("updated 2PC protocol. ", 100)))
	if e != nil || d.ID != original.ID || d.Title != "new100%" || d.CreatedAt != original.CreatedAt {
		t.Fatal(d, e)
	}
	if bad := scalar[int](t, s.Pool, "SELECT count(*) FROM vector_store WHERE metadata->>'document_id'=$1 AND (metadata->>'document_title'<>'new100%' OR metadata->>'filename'<>'new100%.md' OR content LIKE '%PostgreSQL%')", d.ID); bad != 0 {
		t.Fatal("旧代或旧引用残留", bad)
	}
	page, e := s.SearchDocuments(ctx, "100%", 21474837, 100)
	if e != nil || page.Total != 1 || len(page.Items) != 0 {
		t.Fatal(page, e)
	}
	page, e = s.SearchDocuments(ctx, "100_", 0, 20)
	if e != nil || page.Total != 0 {
		t.Fatal(page, e)
	}
	l, e := s.lock(ctx, d.ID)
	if e != nil {
		t.Fatal(e)
	}
	_, e = s.Delete(ctx, d.ID)
	expectBusiness(t, e, api.BusinessConflict, "写入")
	release(l)
	count, e := s.Delete(ctx, d.ID)
	if e != nil || count != d.ChunkCount {
		t.Fatal(count, e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM vector_store WHERE metadata->>'document_id'=$1", d.ID) != 0 {
		t.Fatal("孤儿向量")
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_source WHERE document_id=$1", d.ID) != 0 {
		t.Fatal("来源未级联删除")
	}
}
func TestP6DimensionAndDocumentLeaseFencing(t *testing.T) {
	s := fixtureStore(t)
	p := s.Reader
	p.Properties.EmbeddingDimensions = 12
	_, e := NewStore(context.Background(), s.Pool, p, s.Embedding, s.Locks, "public", "vector_store")
	expectBusiness(t, e, api.EmbeddingDimensionMismatch, "维度")
	for _, identifier := range []string{"a;DROP TABLE x", "a.b", "", "a-b"} {
		if _, e = NewStore(context.Background(), s.Pool, s.Reader, s.Embedding, s.Locks, "public", identifier); e == nil {
			t.Fatal(identifier)
		}
	}
	ctx := context.Background()
	d, e := s.Ingest(ctx, "x.txt", "", []byte("original document text"))
	if e != nil {
		t.Fatal(e)
	}
	s.Embedding = embeddingFunc(func(ctx context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		execSQL(t, s.Pool, "UPDATE runtime_lease SET owner=gen_random_uuid() WHERE resource=$1", "document:"+d.ID)
		return fixtureEmbedding(ctx, r)
	})
	_, e = s.Update(ctx, d.ID, "replacement.txt", []byte("this new content must not commit"))
	if !errors.Is(e, coordination.ErrLeaseLost) {
		t.Fatal(e)
	}
	current, e := s.Document(ctx, d.ID)
	if e != nil || current != d {
		t.Fatal(current, e)
	}
	s.Embedding = embeddingFunc(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1}}}}, nil
	})
	_, e = s.Ingest(ctx, "wrong.txt", "", []byte("wrong dimension vector"))
	expectBusiness(t, e, api.EmbeddingDimensionMismatch, "维度")
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_document") != 1 {
		t.Fatal("失败摄入残留记录")
	}
}
func TestP6IndexJobIdempotencyAtomicResultAndPayloadCleanup(t *testing.T) {
	s := fixtureStore(t)
	j := &Jobs{Store: s, Stats: NewStats()}
	ctx := context.Background()
	id := newID()
	payload := []byte(strings.Repeat("2PC durability ", 100))
	job, e := j.Submit(ctx, id, "", "input.txt", nil, payload)
	if e != nil {
		t.Fatal(e)
	}
	again, e := j.Submit(ctx, id, "", "input.txt", nil, payload)
	if e != nil || again.ID != job.ID {
		t.Fatal(again, e)
	}
	_, e = j.Submit(ctx, id, "", "input.txt", nil, []byte("changed payload"))
	expectBusiness(t, e, api.IdempotencyKeyConflict, "幂等")
	ran, e := j.RunNext(ctx)
	if !ran || e != nil {
		t.Fatal(ran, e)
	}
	job, e = j.Get(ctx, id)
	if e != nil || job.Status != "SUCCEEDED" || job.Progress != 100 || job.Result == nil {
		t.Fatal(job, e)
	}
	if !scalar[bool](t, s.Pool, "SELECT payload IS NULL FROM knowledge_index_job WHERE id=$1", id) {
		t.Fatal("负载未清理")
	}
	d, e := s.Document(ctx, id)
	if e != nil || d.ChunkCount < 1 {
		t.Fatal(d, e)
	}
	bad, e := j.Submit(ctx, newID(), "", "bad.exe", nil, []byte("not accepted"))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = j.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	bad, e = j.Get(ctx, bad.ID)
	if e != nil || bad.Status != "FAILED" {
		t.Fatal(bad, e)
	}
	execSQL(t, s.Pool, "UPDATE knowledge_index_job SET updated_at=now()-interval '8 days' WHERE status IN ('SUCCEEDED','FAILED')")
	if e = j.Cleanup(ctx); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_index_job") != 0 {
		t.Fatal("保留清理未生效")
	}
}
func TestP6IndexJobClaimCompetitionExpiryAndFencing(t *testing.T) {
	s := fixtureStore(t)
	j := &Jobs{Store: s}
	ctx := context.Background()
	payload := []byte("claim token fencing content")
	job, e := j.Submit(ctx, newID(), "", "x.txt", nil, payload)
	if e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	claims := make(chan *Claim, 16)
	errs := make(chan error, 16)
	for i := 0; i < 16; i++ {
		wg.Go(func() {
			c, e := j.Claim(ctx)
			if e != nil {
				errs <- e
			}
			if c != nil {
				claims <- c
			}
		})
	}
	wg.Wait()
	close(claims)
	close(errs)
	for e := range errs {
		t.Fatal(e)
	}
	var claim *Claim
	for c := range claims {
		if claim != nil {
			t.Fatal("重复领取")
		}
		claim = c
	}
	if claim == nil {
		t.Fatal("未领取")
	}
	if ok, e := j.Renew(ctx, claim); e != nil || !ok {
		t.Fatal(ok, e)
	}
	execSQL(t, s.Pool, "UPDATE knowledge_index_job SET lease_until=now()-interval '1 second' WHERE id=$1", job.ID)
	newer, e := j.Claim(ctx)
	if e != nil || newer == nil || newer.Token == claim.Token || newer.Job.Attempts != 2 {
		t.Fatal(newer, e)
	}
	u, e := s.Reader.Prepare(ctx, "x.txt", "", payload)
	if e != nil {
		t.Fatal(e)
	}
	chunks, e := s.PrepareChunks(ctx, u, job.DocumentID)
	if e != nil {
		t.Fatal(e)
	}
	l, e := s.lock(ctx, job.DocumentID)
	if e != nil {
		t.Fatal(e)
	}
	defer release(l)
	e = j.Commit(ctx, claim, l, u, chunks)
	if !errors.Is(e, coordination.ErrLeaseLost) {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_document") != 0 {
		t.Fatal("旧Worker越权提交")
	}
	if e = j.Commit(ctx, newer, l, u, chunks); e != nil {
		t.Fatal(e)
	}
	exhausted, e := j.Submit(ctx, newID(), "", "exhausted.txt", nil, payload)
	if e != nil {
		t.Fatal(e)
	}
	execSQL(t, s.Pool, "UPDATE knowledge_index_job SET status='RUNNING',attempts=3,lease_until=now()-interval '1 second' WHERE id=$1", exhausted.ID)
	if c, e := j.Claim(ctx); e != nil || c != nil {
		t.Fatal(c, e)
	}
	exhausted, e = j.Get(ctx, exhausted.ID)
	if e != nil || exhausted.Status != "FAILED" {
		t.Fatal(exhausted, e)
	}
}
func TestP6QueueCapacityAcrossAPIReplicas(t *testing.T) {
	s := fixtureStore(t)
	j := &Jobs{Store: s}
	var wg sync.WaitGroup
	results := make(chan error, 24)
	for i := 0; i < 24; i++ {
		wg.Go(func() {
			_, e := j.Submit(context.Background(), newID(), "", "x.txt", nil, []byte("queue capacity payload"))
			results <- e
		})
	}
	wg.Wait()
	close(results)
	success, full := 0, 0
	for e := range results {
		if e == nil {
			success++
		} else {
			var b *api.BusinessError
			if !errors.As(e, &b) || b.Code() != api.RateLimited {
				t.Fatal(e)
			}
			full++
		}
	}
	if success != 20 || full != 4 {
		t.Fatal(success, full)
	}
}

var _ = time.Second
