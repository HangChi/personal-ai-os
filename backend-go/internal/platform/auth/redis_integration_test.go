//go:build integration

package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func redisFixture(t *testing.T) *redisclient.Client {
	t.Helper()
	if os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("仅运行专用临时 Redis fixture")
	}
	client, err := redisclient.New(redisclient.Config{URI: os.Getenv("GO_TEST_REDIS_URI"), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := client.Ping(context.Background()); err != nil {
		t.Fatal(err)
	}
	return client
}

func redisSessions(t *testing.T, client *redisclient.Client, timeout time.Duration) *RedisStore {
	t.Helper()
	store, err := NewRedisStore(client, "fixture:"+t.Name(), timeout)
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func TestRedisSessionSharingAndAtomicToken(t *testing.T) {
	first := redisSessions(t, redisFixture(t), time.Minute)
	second := redisSessions(t, redisFixture(t), time.Minute)
	ctx := context.Background()
	id, replacement := sessionID(1), sessionID(2)
	if _, ok, err := first.Load(ctx, id); ok || err != nil {
		t.Fatal("missing must not be an error", err)
	}
	if err := first.Rotate(ctx, "", id, Session{Username: "用户🔐"}); err != nil {
		t.Fatal(err)
	}
	if state, ok, err := second.Load(ctx, id); err != nil || !ok || state.Username != "用户🔐" {
		t.Fatal("cross-client session", err)
	}
	var group sync.WaitGroup
	tokens := make(chan string, 100)
	for i := 0; i < 100; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			store := first
			if i%2 == 0 {
				store = second
			}
			state, ok, err := store.EnsureCSRF(ctx, id, fmt.Sprint(i))
			if err != nil || !ok {
				t.Error("CSRF first-write", err)
				return
			}
			tokens <- state.CSRFToken
		}(i)
	}
	group.Wait()
	close(tokens)
	firstToken := ""
	for token := range tokens {
		if firstToken == "" {
			firstToken = token
		}
		if token != firstToken {
			t.Fatal("token raced")
		}
	}
	if err := second.Rotate(ctx, id, replacement, Session{Username: "operator"}); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := first.Load(ctx, id); ok || err != nil {
		t.Fatal("old session survived", err)
	}
	if _, ok, err := first.EnsureCSRF(ctx, id, "old"); ok || err != nil {
		t.Fatal("old ID resurrected", err)
	}
	if state, ok, err := first.Load(ctx, replacement); !ok || err != nil || state.Username != "operator" || state.CSRFToken != "" {
		t.Fatal("rotation state", err)
	}
	if err := first.Rotate(ctx, "", id, Session{Username: "old"}); err != nil {
		t.Fatal(err)
	}
	if err := second.Rotate(ctx, id, replacement, Session{}); err != ErrSessionCollision {
		t.Fatal("collision must fail before deleting old", err)
	}
	if state, ok, err := first.Load(ctx, id); !ok || err != nil || state.Username != "old" {
		t.Fatal("collision deleted old")
	}
	if err := second.Delete(ctx, replacement); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := first.Load(ctx, replacement); ok || err != nil {
		t.Fatal("logout did not propagate", err)
	}
	if _, ok, err := first.EnsureCSRF(ctx, replacement, "stale"); ok || err != nil {
		t.Fatal("logout resurrected", err)
	}
}

func TestRedisSessionTTLInvalidStateAndNamespaceIsolation(t *testing.T) {
	client := redisFixture(t)
	store := redisSessions(t, client, time.Second)
	ctx := context.Background()
	id := sessionID(4)
	if err := store.Rotate(ctx, "", id, Session{Username: "admin"}); err != nil {
		t.Fatal(err)
	}
	value, err := client.Eval(ctx, `return redis.call('PTTL',KEYS[1])`, []string{store.key(id)})
	if err != nil || value.(int64) <= 0 || value.(int64) > 1000 {
		t.Fatal("TTL missing", err)
	}
	if _, err := client.Eval(ctx, `return redis.call('PEXPIRE',KEYS[1],100)`, []string{store.key(id)}); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := store.EnsureCSRF(ctx, id, "token"); !ok || err != nil {
		t.Fatal(err)
	}
	value, err = client.Eval(ctx, `return redis.call('PTTL',KEYS[1])`, []string{store.key(id)})
	if err != nil || value.(int64) > 100 {
		t.Fatal("token update reset expiry", err)
	}
	if _, ok, err := store.Load(ctx, id); !ok || err != nil {
		t.Fatal(err)
	}
	value, err = client.Eval(ctx, `return redis.call('PTTL',KEYS[1])`, []string{store.key(id)})
	if err != nil || value.(int64) < 800 {
		t.Fatal("load did not refresh expiry", err)
	}
	time.Sleep(1100 * time.Millisecond)
	if _, ok, err := store.Load(ctx, id); ok || err != nil {
		t.Fatal("expired session accepted", err)
	}
	for _, raw := range []string{`bad-json`, `{"v":2,"username":"admin","csrfToken":"x"}`, `{"v":1,"username":true,"csrfToken":"x"}`, `{"v":1,"username":"admin","csrfToken":"x"}`} {
		// 最后一个结构合法但没有 TTL，同样必须拒绝。
		if _, err := client.Eval(ctx, `return redis.call('SET',KEYS[1],ARGV[1])`, []string{store.key(id)}, raw); err != nil {
			t.Fatal(err)
		}
		if _, _, err := store.Load(ctx, id); err == nil {
			t.Fatal("corrupt/persistent session accepted")
		}
		if _, _, err := store.EnsureCSRF(ctx, id, "stale"); err == nil {
			t.Fatal("corrupt session updated")
		}
	}
	if err := store.Delete(ctx, id); err != nil {
		t.Fatal(err)
	}
	springKey := "fixture:" + t.Name() + ":sessions:" + id
	if _, err := client.Eval(ctx, `return redis.call('SET',KEYS[1],ARGV[1])`, []string{springKey}, "synthetic-java-session"); err != nil {
		t.Fatal(err)
	}
	if err := store.Rotate(ctx, "", id, Session{Username: "go"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Delete(ctx, id); err != nil {
		t.Fatal(err)
	}
	value, err = client.Eval(ctx, `return redis.call('GET',KEYS[1])`, []string{springKey})
	if err != nil || value != "synthetic-java-session" {
		t.Fatal("Go touched Spring key", err)
	}
}

func TestRedisSessionCrossProcessInvalidation(t *testing.T) {
	client := redisFixture(t)
	store := redisSessions(t, client, time.Minute)
	id := sessionID(6)
	if err := store.Rotate(context.Background(), "", id, Session{Username: "operator"}); err != nil {
		t.Fatal(err)
	}
	childContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	child := exec.CommandContext(childContext, os.Args[0], "-test.run=^TestRedisSessionChild$")
	child.Env = append(os.Environ(), "GO_REDIS_SESSION_CHILD=read-delete", "GO_REDIS_SESSION_NAMESPACE=fixture:"+t.Name(), "GO_REDIS_SESSION_ID="+id)
	if output, err := child.CombinedOutput(); err != nil {
		t.Fatalf("独立进程读取/注销失败: %v\n%s", err, output)
	}
	if _, ok, err := store.Load(context.Background(), id); ok || err != nil {
		t.Fatal("subprocess logout not shared", err)
	}
}

func TestRedisSessionChild(t *testing.T) {
	if os.Getenv("GO_REDIS_SESSION_CHILD") != "read-delete" {
		t.Skip("仅供跨进程测试")
	}
	client := redisFixture(t)
	store, err := NewRedisStore(client, os.Getenv("GO_REDIS_SESSION_NAMESPACE"), time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	id := os.Getenv("GO_REDIS_SESSION_ID")
	if state, ok, err := store.Load(context.Background(), id); !ok || err != nil || state.Username != "operator" {
		t.Fatal("cross-process identity missing", err)
	}
	if err := store.Delete(context.Background(), id); err != nil {
		t.Fatal(err)
	}
}

func TestRedisSharedHTTPServersAndNoFallback(t *testing.T) {
	firstClient, secondClient := redisFixture(t), redisFixture(t)
	firstStore := redisSessions(t, firstClient, time.Minute)
	secondStore := redisSessions(t, secondClient, time.Minute)
	server := func(store SessionStore) *httptest.Server {
		service, _ := newService(enabledOptions(), &testCodec{}, time.Now)
		layer, err := NewHTTP(service, store, HTTPOptions{CSRFEnabled: true, CookieName: "SESSION"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err != nil {
			t.Fatal(err)
		}
		result := httptest.NewServer(httpmiddleware.RequestIDMiddleware(layer.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))))
		t.Cleanup(result.Close)
		return result
	}
	a, b := server(firstStore), server(secondStore)
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar, Timeout: 5 * time.Second}
	send := func(base, method, path, body, token string) (int, map[string]any) {
		t.Helper()
		r, _ := http.NewRequest(method, base+path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("X-CSRF-TOKEN", token)
		}
		response, err := client.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var result map[string]any
		if response.StatusCode != 204 {
			if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
				t.Fatal(err)
			}
		}
		return response.StatusCode, result
	}
	_, initial := send(a.URL, "GET", "/api/v1/auth/csrf", "", "")
	token := initial["data"].(map[string]any)["token"].(string)
	if status, _ := send(a.URL, "POST", "/api/v1/auth/login", loginJSON, token); status != 200 {
		t.Fatal("login", status)
	}
	base, _ := url.Parse(a.URL)
	cookies := jar.Cookies(base)
	if len(cookies) != 1 || cookies[0].Name != "SESSION" {
		t.Fatal("shared cookie")
	}
	stale := cookies[0]
	if status, _ := send(b.URL, "GET", "/api/v1/tasks", "", ""); status != 204 {
		t.Fatal("second server did not read identity", status)
	}
	_, current := send(b.URL, "GET", "/api/v1/auth/csrf", "", "")
	token = current["data"].(map[string]any)["token"].(string)
	if status, _ := send(b.URL, "POST", "/api/v1/auth/logout", "", token); status != 200 {
		t.Fatal("second server logout", status)
	}
	r, _ := http.NewRequest("GET", a.URL+"/api/v1/tasks", nil)
	r.AddCookie(stale)
	response, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 401 {
		t.Fatal("old cookie survived logout on other server")
	}
	if err := firstClient.Close(); err != nil {
		t.Fatal(err)
	}
	r, _ = http.NewRequest("GET", a.URL+"/api/v1/auth/me", nil)
	r.AddCookie(stale)
	response, err = http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 500 {
		t.Fatal("closed Redis fell back to local/anonymous success", response.StatusCode)
	}
}
