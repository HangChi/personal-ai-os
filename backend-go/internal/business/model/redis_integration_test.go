//go:build integration

package model

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func TestRedisEmbeddingCacheTLSACLIsolationTTLAndFaultFallback(t *testing.T) {
	if os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("仅运行于专用临时Redis")
	}
	uri := os.Getenv("GO_TEST_REDIS_URI")
	if !strings.Contains(uri, "@127.0.0.1:") {
		t.Fatal("拒绝现有Redis")
	}
	root, e := redisclient.New(redisclient.Config{URI: uri, RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if e != nil {
		t.Fatal(e)
	}
	defer root.Close()
	aclURI := os.Getenv("GO_TEST_REDIS_EMBEDDING_URI")
	if aclURI == "" {
		t.Fatal("缺少专用embedding ACL账号")
	}
	client, e := redisclient.New(redisclient.Config{URI: aclURI, RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	ctx := context.Background()
	cache, e := NewRedisEmbeddingCache(client, "fixture:embedding", 150*time.Millisecond)
	if e != nil {
		t.Fatal(e)
	}
	cache.Put(ctx, "endpoint-a", "digest", []float32{1, 2}, 80*time.Millisecond)
	if v := cache.Get(ctx, "endpoint-a", "digest"); len(v) != 2 || v[0] != 1 {
		t.Fatal("shared cache miss")
	}
	if cache.Get(ctx, "endpoint-b", "digest") != nil {
		t.Fatal("model identity collision")
	}
	time.Sleep(100 * time.Millisecond)
	if cache.Get(ctx, "endpoint-a", "digest") != nil {
		t.Fatal("TTL not applied")
	}
	for _, bad := range []string{`oops`, `null`, `[]`, `["NaN"]`, `[1e999]`} {
		_, e = root.Eval(ctx, `return redis.call('SET',KEYS[1],ARGV[1])`, []string{cache.key("endpoint-a", "bad")}, bad)
		if e != nil {
			t.Fatal(e)
		}
		if v := cache.Get(ctx, "endpoint-a", "bad"); v != nil {
			t.Fatal("malformed vector accepted")
		}
	}
	for _, cmd := range []string{`return redis.call('KEYS','*')`, `return redis.call('FLUSHALL')`, `return redis.call('GET','other:private')`} {
		if _, e = client.Eval(ctx, cmd, nil); e == nil {
			t.Fatal("ACL allowed unrelated command/key")
		}
	}
	other, e := NewRedisEmbeddingCache(client, "other-namespace", 150*time.Millisecond)
	if e != nil {
		t.Fatal(e)
	}
	if other.Get(ctx, "endpoint-a", "digest") != nil || other.Stats.Snapshot().Counts["read_error"] != 1 {
		t.Fatal("namespace escape")
	}
	calls := 0
	delegate := embeddingFunc(func(_ context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
		calls++
		out := EmbeddingResponse{}
		for i := range r.Input {
			out.Results = append(out.Results, EmbeddingResult{i, []float32{3, 4}})
		}
		return out, nil
	})
	one, _ := NewCachedEmbedding(delegate, "same-model", true, 10, time.Minute, cache)
	two, _ := NewCachedEmbedding(delegate, "same-model", true, 10, time.Minute, cache)
	if _, e = one.Embed(ctx, EmbeddingRequest{Input: []string{"private-original-text"}}); e != nil {
		t.Fatal(e)
	}
	v, e := two.Embed(ctx, EmbeddingRequest{Input: []string{"private-original-text"}})
	if e != nil || calls != 1 || v.Results[0].Vector[0] != 3 || two.Stats.Snapshot().Counts["shared_hit"] != 1 {
		t.Fatal("cross-instance shared miss", e)
	}
	keys, e := root.Eval(ctx, `return redis.call('KEYS',ARGV[1])`, nil, "personalos:embedding:*")
	if e != nil || strings.Contains(strings.TrimSpace(strings.ReplaceAll(cache.key("identity", "digest"), "digest", "")), "private-original-text") {
		t.Fatal("plaintext cache key")
	}
	for _, key := range keys.([]any) {
		if strings.Contains(key.(string), "private-original-text") {
			t.Fatal("plaintext stored in key")
		}
	}
	_ = client.Close()
	start := time.Now()
	if _, e = two.Embed(ctx, EmbeddingRequest{Input: []string{"fault fallback"}}); e != nil || calls != 2 || time.Since(start) > time.Second || cache.Stats.Snapshot().Counts["write_error"] == 0 {
		t.Fatal("Redis fault blocked delegate", e)
	}
}
