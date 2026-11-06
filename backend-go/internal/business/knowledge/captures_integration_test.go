//go:build integration

package knowledge

import (
	"context"
	"errors"
	"personal-ai-os/backend-go/internal/business/model"
	"strings"
	"sync"
	"testing"
)

type captureFixture struct{ content string }

func (f *captureFixture) Fetch(_ context.Context, url string) (WebPage, error) {
	canonical, e := CanonicalURL(url)
	return WebPage{canonical, "公开资料", f.content}, e
}
func TestCaptureAtomicVersions(t *testing.T) {
	store := fixtureStore(t)
	ctx := context.Background()
	execSQL(t, store.Pool, "TRUNCATE knowledge_capture CASCADE")
	f := &captureFixture{strings.Repeat("旧版公开资料", 30)}
	s := &Captures{Pool: store.Pool, Fetcher: f}
	jobs := &Jobs{Store: store}
	var wg sync.WaitGroup
	results := make(chan Capture, 2)
	errs := make(chan error, 2)
	for _, url := range []string{"https://example.com/article#one", "https://EXAMPLE.com:443/article#two"} {
		wg.Add(1)
		go func(u string) { defer wg.Done(); v, e := s.Capture(ctx, u); results <- v; errs <- e }(url)
	}
	wg.Wait()
	close(results)
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	var id string
	for v := range results {
		if id != "" && id != v.ID {
			t.Fatal(v)
		}
		id = v.ID
		if v.CurrentVersion != 0 || v.DocumentID != nil {
			t.Fatal(v)
		}
	}
	if scalar[int](t, store.Pool, "SELECT count(*) FROM knowledge_index_job") != 1 {
		t.Fatal("duplicate index job")
	}
	if _, e := jobs.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	old, e := s.Get(ctx, id)
	if e != nil || old.CurrentVersion != 1 {
		t.Fatal(old, e)
	}
	unchanged, e := s.Refresh(ctx, id)
	if e != nil || unchanged.PendingJobID != nil || scalar[int](t, store.Pool, "SELECT count(*) FROM knowledge_capture_version") != 1 {
		t.Fatal(unchanged, e)
	}
	f.content = strings.Repeat("新版公开资料", 30)
	if _, e := s.Refresh(ctx, id); e != nil {
		t.Fatal(e)
	}
	store.Embedding = embeddingFunc(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		return model.EmbeddingResponse{}, errors.New("private upstream")
	})
	jobs.RunNext(ctx)
	prior, e := s.Get(ctx, id)
	if e != nil || prior.CurrentVersion != 1 || scalar[int](t, store.Pool, "SELECT count(*) FROM vector_store WHERE content LIKE '%旧版%'") < 1 {
		t.Fatal(prior, e)
	}
	store.Embedding = embeddingFunc(fixtureEmbedding)
	execSQL(t, store.Pool, "UPDATE knowledge_index_job SET available_at=now()-interval '1 second'")
	if _, e := jobs.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	next, e := s.Get(ctx, id)
	if e != nil || next.CurrentVersion != 2 || *next.DocumentID != *old.DocumentID {
		t.Fatal(next, e)
	}
	if scalar[int](t, store.Pool, "SELECT count(*) FROM knowledge_document") != 1 {
		t.Fatal("duplicate document")
	}
}
