//go:build integration

package knowledge

import (
	"context"
	"errors"
	"os"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
	"strconv"
	"strings"
	"testing"
)

func TestP6RedisCandidateIsolationTTLGenerationAndFailure(t *testing.T) {
	if os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("需要本轮独立Redis")
	}
	s := fixtureStore(t)
	ctx := context.Background()
	client, e := redisclient.New(redisclient.Config{URI: os.Getenv("GO_TEST_REDIS_CANDIDATE_URI"), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = client.Close() })
	cache, e := NewCandidateCache(s, client, "fixture:candidate", "provider/model/config")
	if e != nil {
		t.Fatal(e)
	}
	other, _ := NewCandidateCache(s, client, "fixture:other", "provider/model/config")
	changed, _ := NewCandidateCache(s, client, "fixture:candidate", "different-model")
	if cache.Prefix == other.Prefix || cache.Prefix == changed.Prefix || strings.Contains(cache.Prefix, "fixture") {
		t.Fatal("隔离或敏感键策略错误")
	}
	calls := 0
	load := func(bool) ([]Hit, error) {
		calls++
		return []Hit{{DocumentID: newID(), Content: "cached content", MatchedBy: "VECTOR"}}, nil
	}
	ready := func() bool { return false }
	query := "sensitive query"
	first, e := cache.Get(ctx, query, 4, false, ready, load)
	if e != nil {
		t.Fatal(e)
	}
	second, e := cache.Get(ctx, query, 4, false, ready, load)
	if e != nil || calls != 1 || first[0].DocumentID != second[0].DocumentID {
		t.Fatal(calls, second, e)
	}
	version, e := cache.generation(ctx)
	if e != nil {
		t.Fatal(e)
	}
	key := cache.Prefix + version + ":" + digest(strconv.Itoa(4)+"\nfalse\n"+query)
	ttl, e := client.Eval(ctx, `return redis.call('PTTL',KEYS[1])`, []string{key})
	if e != nil || ttl.(int64) < 18000 || ttl.(int64) > 20000 {
		t.Fatal(ttl, e)
	}
	if _, e = other.Get(ctx, query, 4, false, ready, load); e != nil || calls != 2 {
		t.Fatal(calls, e)
	}
	if _, e = cache.Get(ctx, query, 5, false, ready, load); e != nil || calls != 3 {
		t.Fatal(calls, e)
	}
	if _, e = cache.Get(ctx, query, 4, false, func() bool { return true }, load); e != nil || calls != 4 {
		t.Fatal(calls, e)
	}
	_, e = s.Ingest(ctx, "new.txt", "", []byte("invalidate generation content"))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = cache.Get(ctx, query, 4, false, ready, load); e != nil || calls != 5 {
		t.Fatal(calls, e)
	}
	for _, inTx := range []bool{true, true} {
		if _, e = cache.Get(ctx, query, 4, inTx, ready, load); e != nil {
			t.Fatal(e)
		}
	}
	if calls != 7 {
		t.Fatal(calls)
	}
	during := func(bool) ([]Hit, error) {
		calls++
		execSQL(t, s.Pool, "UPDATE knowledge_source SET authority='changed'")
		return first, nil
	}
	if _, e = cache.Get(ctx, "mutation", 4, false, ready, during); e != nil {
		t.Fatal(e)
	}
	if _, e = cache.Get(ctx, "mutation", 4, false, ready, load); e != nil || calls != 9 {
		t.Fatal(calls, e)
	}
	cache.Close()
	if _, e = cache.Get(ctx, query, 4, false, ready, load); e != nil || calls != 10 {
		t.Fatal(calls, e)
	}
	if e = client.Ping(ctx); e != nil {
		t.Fatal("关闭借用客户端", e)
	}
	broken, _ := NewCandidateCache(s, errorCache{}, "fixture:candidate", "")
	if _, e = broken.Get(ctx, query, 4, false, ready, load); e != nil || calls != 11 {
		t.Fatal(calls, e)
	}
}

type errorCache struct{}

func (errorCache) Eval(context.Context, string, []string, ...any) (any, error) {
	return nil, errors.New("Redis unavailable")
}
