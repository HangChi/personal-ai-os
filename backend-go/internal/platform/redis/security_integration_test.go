//go:build integration

package redisclient_test

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"personal-ai-os/backend-go/internal/platform/auth"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func aclClient(t *testing.T, variable string) (*redisclient.Client, *goredis.Client) {
	t.Helper()
	if os.Getenv("GO_TEST_REDIS") != "disposable" || os.Getenv(variable) == "" {
		t.Skip("仅运行脚本创建的临时 Redis ACL 账号")
	}
	uri := os.Getenv(variable)
	root := []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))
	client, err := redisclient.New(redisclient.Config{URI: uri, RootCertPEM: root})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	// 原生客户端只用于发起专用 API 未公开的管理命令，证明服务端 ACL 拒绝。
	options, err := goredis.ParseURL(uri)
	if err != nil {
		t.Fatal("临时 ACL URI 无效")
	}
	options.Protocol, options.MaxRetries, options.DisableIdentity = 2, -1, true
	if options.TLSConfig != nil {
		roots := x509.NewCertPool()
		if !roots.AppendCertsFromPEM(root) {
			t.Fatal("临时根证书无效")
		}
		options.TLSConfig.RootCAs = roots
		options.TLSConfig.MinVersion = tls.VersionTLS12
	}
	probe := goredis.NewClient(options)
	t.Cleanup(func() { _ = probe.Close() })
	if err := client.Ping(context.Background()); err != nil {
		t.Fatal("受限账号握手/探测失败", err)
	}
	return client, probe
}

func requirePermissionDenied(t *testing.T, err error) {
	t.Helper()
	if err == nil || !strings.Contains(err.Error(), "NOPERM") {
		t.Fatal("服务端未返回 ACL 权限拒绝")
	}
}

func TestRedisSeparateLeastPrivilegeAccounts(t *testing.T) {
	sessions, sessionProbe := aclClient(t, "GO_TEST_REDIS_SESSION_URI")
	rates, rateProbe := aclClient(t, "GO_TEST_REDIS_RATE_URI")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	store, err := auth.NewRedisStore(sessions, "fixture:acl-session", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	id, nextID := strings.Repeat("a", 64), strings.Repeat("b", 64)
	if err := store.Rotate(ctx, "", id, auth.Session{Username: "用户🔐"}); err != nil {
		t.Fatal("受限账号无法创建会话", err)
	}
	if state, ok, err := store.EnsureCSRF(ctx, id, "test-csrf"); err != nil || !ok || state.CSRFToken != "test-csrf" {
		t.Fatal("受限账号无法原子写入令牌", err)
	}
	if err := store.Rotate(ctx, id, nextID, auth.Session{Username: "admin"}); err != nil {
		t.Fatal("受限账号无法轮换", err)
	}
	if _, ok, err := store.Load(ctx, id); err != nil || ok {
		t.Fatal("旧会话未失效", err)
	}
	if state, ok, err := store.Load(ctx, nextID); err != nil || !ok || state.Username != "admin" {
		t.Fatal("受限账号无法读续期", err)
	}
	if err := store.Delete(ctx, nextID); err != nil {
		t.Fatal("受限账号无法注销", err)
	}
	bucket, err := ratelimit.NewRedisBucket(rates, "fixture:acl-rate")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		allowed, err := bucket.Consume(ctx, "model-rate", 2, time.Hour)
		if err != nil || allowed != (i < 2) {
			t.Fatal("受限账号共享额度错误", err)
		}
	}
	for _, probe := range []*goredis.Client{sessionProbe, rateProbe} {
		for _, command := range [][]any{{"CONFIG", "GET", "*"}, {"ACL", "LIST"}, {"FLUSHALL"}, {"KEYS", "*"}} {
			requirePermissionDenied(t, probe.Do(ctx, command...).Err())
		}
		// 即使仍可 EVAL，也不得通过脚本绕过键空间或命令 ACL。
		requirePermissionDenied(t, probe.Get(ctx, "spring:session:sessions:java-session").Err())
		requirePermissionDenied(t, probe.Eval(ctx, `return redis.call('GET',KEYS[1])`, []string{"spring:session:sessions:java-session"}).Err())
	}
	requirePermissionDenied(t, rateProbe.Get(ctx, "fixture:acl-session:go:v1:session:"+nextID).Err())
	requirePermissionDenied(t, sessionProbe.HSet(ctx, "fixture:acl-session:go:v1:session:"+nextID, "field", "value").Err())
	if err := sessions.Ping(ctx); err != nil {
		t.Fatal("越权拒绝破坏了合法连接", err)
	}
}

type postgresMustNotRun struct{ calls atomic.Int32 }

func (probe *postgresMustNotRun) Consume(context.Context, string, int, time.Duration) (bool, error) {
	probe.calls.Add(1)
	return true, nil
}

type acquisitionProbe struct{ failures atomic.Int32 }

func (probe *acquisitionProbe) RecordAcquisition(_ ratelimit.Backend, _ time.Duration, failed bool) {
	if failed {
		probe.failures.Add(1)
	}
}

func TestRedisACLDenialFailsClosedAtHTTPAndRateLimit(t *testing.T) {
	client, _ := aclClient(t, "GO_TEST_REDIS_SESSION_URI")
	store, err := auth.NewRedisStore(client, "fixture:ungranted", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	options := auth.DefaultOptions()
	options.Enabled, options.Password = true, "synthetic-acl-password"
	service, err := auth.New(options)
	if err != nil {
		t.Fatal(err)
	}
	layer, err := auth.NewHTTP(service, store, auth.HTTPOptions{CookieName: "SESSION"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	var business atomic.Int32
	handler := layer.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		business.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	request := httptest.NewRequest("GET", "/api/v1/auth/me", nil)
	request.AddCookie(&http.Cookie{Name: "SESSION", Value: strings.Repeat("c", 64)})
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != 500 || business.Load() != 0 || len(response.Result().Cookies()) != 0 || strings.Contains(response.Body.String(), "NOPERM") {
		t.Fatal("会话 ACL 失败没有保持脱敏拒绝语义")
	}
	// 限流客户端误配成会话账号，既不能成功也不能转用 PostgreSQL。
	bucket, err := ratelimit.NewRedisBucket(client, "fixture:acl-rate")
	if err != nil {
		t.Fatal(err)
	}
	postgres, observer := &postgresMustNotRun{}, &acquisitionProbe{}
	limiter, err := ratelimit.NewShared(ratelimit.DefaultOptions(), postgres, bucket, observer)
	if err != nil {
		t.Fatal(err)
	}
	if allowed, err := limiter.TryAcquire(context.Background()); allowed || err != nil || postgres.calls.Load() != 0 || observer.failures.Load() != 1 {
		t.Fatal("ACL 失败回退或漏计错误", err)
	}
	response = httptest.NewRecorder()
	httpmiddleware.RateLimitMiddleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { business.Add(1) }), limiter,
		slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(response, httptest.NewRequest("POST", "/api/v1/chat/sessions/fixture/stream", nil))
	if response.Code != 429 || business.Load() != 0 || postgres.calls.Load() != 0 {
		t.Fatal("Redis ACL 故障错误放行 HTTP")
	}
}
