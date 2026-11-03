//go:build integration

package app

import (
	"context"
	"database/sql"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func fixtureAppRedis(t *testing.T, key string) *redisclient.Client {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" || os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("仅运行脚本同时新建的PostgreSQL/Redis组合")
	}
	uri := os.Getenv(key)
	if uri == "" {
		t.Fatal("缺少本次专用ACL客户端")
	}
	client, err := redisclient.New(redisclient.Config{URI: uri, RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	return client
}

func TestFoundationRedisACLTwoInstancesAndFailureIsolation(t *testing.T) {
	session1, rate1 := fixtureAppRedis(t, "GO_TEST_REDIS_SESSION_URI"), fixtureAppRedis(t, "GO_TEST_REDIS_RATE_URI")
	session2, rate2 := fixtureAppRedis(t, "GO_TEST_REDIS_SESSION_URI"), fixtureAppRedis(t, "GO_TEST_REDIS_RATE_URI")
	values := map[string]string{
		"spring.profiles.active": "monitoring", "app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true",
		"app.shared-session.enabled": "true", "app.shared-session.namespace": "fixture:app-sessions",
		"app.rate-limit.redis.enabled": "true", "app.rate-limit.redis.namespace": "fixture:app-rate", "app.rate-limit.capacity": "2", "app.rate-limit.period": "1h",
	}
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if err != nil {
		t.Fatal(err)
	}
	open := func(session, rate *redisclient.Client) *Foundation {
		t.Helper()
		f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-app-redis-fixture"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", SessionRedis: session, RateRedis: rate, Business: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if user, ok := auth.CurrentUsername(r.Context()); !ok || user != "admin" {
				t.Error("共享身份未进入业务")
			}
			w.WriteHeader(204)
		})}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := f.Close(ctx); err != nil {
				t.Error(err)
			}
		})
		return f
	}
	f1, f2 := open(session1, rate1), open(session2, rate2)
	appExec(t, f1, "DELETE FROM runtime_rate_bucket")
	s1, s2 := httptest.NewServer(f1.Handler()), httptest.NewServer(f2.Handler())
	defer s1.Close()
	defer s2.Close()
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar, Timeout: 5 * time.Second}
	status, payload := appRequest(t, client, "GET", s1.URL+"/api/v1/auth/csrf", "", "")
	if status != 200 {
		t.Fatal("CSRF读取失败")
	}
	token := payload["data"].(map[string]any)["token"].(string)
	status, _ = appRequest(t, client, "POST", s2.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`)
	if status != 200 {
		t.Fatal("实例2不能读取实例1的CSRF")
	}
	status, payload = appRequest(t, client, "GET", s1.URL+"/api/v1/auth/me", "", "")
	if status != 200 || payload["data"].(map[string]any)["username"] != "admin" {
		t.Fatal("登录会话未跨实例共享")
	}
	status, payload = appRequest(t, client, "GET", s1.URL+"/api/v1/auth/csrf", "", "")
	token = payload["data"].(map[string]any)["token"].(string)
	for index, url := range []string{s1.URL, s2.URL, s1.URL} {
		status, _ := appRequest(t, client, "POST", url+"/api/v1/settings/model/test", token, "")
		want := 204
		if index == 2 {
			want = 429
		}
		if status != want {
			t.Fatal("Redis预算未跨应用实例共享", status)
		}
	}
	if err := rate1.Close(); err != nil {
		t.Fatal(err)
	}
	status, _ = appRequest(t, client, "POST", s1.URL+"/api/v1/settings/model/test", token, "")
	if status != 429 {
		t.Fatal("Redis限流错误未拒绝")
	}
	w := httptest.NewRecorder()
	f1.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	if !strings.Contains(w.Body.String(), `personalos_rate_limit_errors_total{backend="redis"} 1`) {
		t.Fatal("应用未记录Redis限流错误")
	}
	if strings.Contains(w.Body.String(), "fixture-password") {
		t.Fatal("指标泄露凭据")
	}
	count := -1
	if err := f1.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		return c.QueryRowContext(context.Background(), "SELECT count(*) FROM runtime_rate_bucket WHERE name='model-rate'").Scan(&count)
	}); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("Redis故障发生PostgreSQL回退")
	}
	status, _ = appRequest(t, client, "GET", s1.URL+"/api/v1/auth/me", "", "")
	if status != 200 {
		t.Fatal("限流客户端故障污染会话客户端")
	}
	w = httptest.NewRecorder()
	f1.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
	if w.Code != 503 {
		t.Fatal("启用的Redis失败未反映管理健康")
	}
	if err := session2.Close(); err != nil {
		t.Fatal(err)
	}
	status, _ = appRequest(t, client, "GET", s2.URL+"/api/v1/auth/me", "", "")
	if status != 500 {
		t.Fatal("会话故障不应回退本地会话")
	}
	if err := f1.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := session1.Ping(context.Background()); err != nil {
		t.Fatal("Foundation关闭了借用的Redis客户端")
	}
}
