//go:build integration

package app

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/migration"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

func fixtureAppConnector(t *testing.T, name string) driver.Connector {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("仅接受已执行真实Flyway的独立临时库")
	}
	o := database.DefaultPostgresOptions()
	o.Host, o.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	o.Username, o.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil {
		t.Fatal("临时端口无效")
	}
	if o.Host != "127.0.0.1" || o.Database != "personalos_go_test" || o.Username != "go_fixture" {
		t.Fatal("只允许专用临时库")
	}
	o.Port, o.SSLMode, o.RootCert = port, os.Getenv("GO_TEST_POSTGRES_SSL_MODE"), os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	o.ApplicationName = name
	connector, err := database.NewPostgresConnector(o)
	if err != nil {
		t.Fatal(err)
	}
	return connector
}

func fixtureFoundation(t *testing.T, values map[string]string, business http.Handler) *Foundation {
	t.Helper()
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if err != nil {
		t.Fatal(err)
	}
	f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-app-fixture"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", AllowBaselineV1: os.Getenv("GO_TEST_FLYWAY_BASELINE") == "disposable", Business: business}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := f.Close(ctx); err != nil {
			t.Error(err)
		}
	})
	return f
}

func appExec(t *testing.T, f *Foundation, query string) {
	t.Helper()
	if err := f.Pool().WithConn(context.Background(), func(c *sql.Conn) error { _, err := c.ExecContext(context.Background(), query); return err }); err != nil {
		t.Fatal(err)
	}
}

func appRequest(t *testing.T, c *http.Client, method, url, token, body string) (int, map[string]any) {
	t.Helper()
	r, err := http.NewRequest(method, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if body != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		r.Header.Set("X-CSRF-TOKEN", token)
	}
	r.Header.Set(httpmiddleware.RequestIDHeader, "fixture-app")
	response, err := c.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.Header.Get(httpmiddleware.RequestIDHeader) != "fixture-app" {
		t.Fatal("缺少请求ID")
	}
	var payload map[string]any
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil && err != io.EOF {
		t.Fatal(err)
	}
	return response.StatusCode, payload
}

func TestFoundationPostgresHTTPAndSharedBudget(t *testing.T) {
	values := map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true", "app.rate-limit.capacity": "2", "app.rate-limit.period": "1h", "app.model-max-concurrent-requests": "1"}
	business := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, ok := auth.CurrentUsername(r.Context()); !ok || user != "admin" {
			t.Error("身份未传到业务")
		}
		w.WriteHeader(204)
	})
	f1 := fixtureFoundation(t, values, business)
	f2 := fixtureFoundation(t, values, business)
	appExec(t, f1, "DELETE FROM runtime_rate_bucket")
	if f1.History().CurrentVersion != 42 || f1.Pool().Stats().MaxOpenConnections != 10 {
		t.Fatal("历史/连接池组合错误")
	}
	s1 := httptest.NewServer(f1.Handler())
	defer s1.Close()
	s2 := httptest.NewServer(f2.Handler())
	defer s2.Close()
	c := s1.Client()
	c.Timeout = 10 * time.Second
	code, payload := appRequest(t, c, "GET", s1.URL+"/api/v1/health", "", "")
	data := payload["data"].(map[string]any)
	if code != 200 || data["status"] != "UP" || data["components"].(map[string]any)["ai"] != "NOT_CONFIGURED" {
		t.Fatal("健康探测未组合真实库")
	}
	for index, url := range []string{s1.URL, s2.URL, s1.URL} {
		code, _ := appRequest(t, c, "POST", url+"/api/v1/settings/model/test", "", "")
		want := 403
		if index == 2 {
			want = 429
		}
		if code != want {
			t.Fatalf("两个实例未在CSRF前共享预算: %d", code)
		}
	}
	jar, _ := cookiejar.New(nil)
	c.Jar = jar
	code, payload = appRequest(t, c, "GET", s1.URL+"/api/v1/auth/csrf", "", "")
	if code != 200 {
		t.Fatal("CSRF获取失败")
	}
	token := payload["data"].(map[string]any)["token"].(string)
	code, _ = appRequest(t, c, "POST", s1.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`)
	if code != 200 {
		t.Fatal("登录失败")
	}
	code, _ = appRequest(t, c, "GET", s1.URL+"/api/v1/tasks", "", "")
	if code != 204 {
		t.Fatal("登录身份未接线")
	}
	code, payload = appRequest(t, c, "GET", s1.URL+"/api/v1/auth/csrf", "", "")
	newToken := payload["data"].(map[string]any)["token"].(string)
	if code != 200 || token == newToken {
		t.Fatal("登录未轮换CSRF")
	}
	code, _ = appRequest(t, c, "POST", s1.URL+"/api/v1/auth/logout", newToken, "")
	if code != 200 {
		t.Fatal("注销失败")
	}
	code, _ = appRequest(t, c, "GET", s1.URL+"/api/v1/tasks", "", "")
	if code != 401 {
		t.Fatal("注销后仍通过认证")
	}
	if f1.Pool().Stats().InUse != 0 || f2.Pool().Stats().InUse != 0 {
		t.Fatal("HTTP请求泄露连接")
	}
}

func TestFoundationPostgresDrainAndCancellation(t *testing.T) {
	entered := make(chan struct{})
	exited := make(chan struct{})
	values := map[string]string{"app.rate-limit.enabled": "false", "app.model-max-concurrent-requests": "1"}
	f1 := fixtureFoundation(t, values, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(entered); <-r.Context().Done(); close(exited) }))
	f2 := fixtureFoundation(t, values, nil)
	requestDone := make(chan struct{})
	go func() {
		defer close(requestDone)
		f1.Handler().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("处理器未进入")
	}
	if f1.Pool().Stats().InUse != 0 {
		t.Fatal("网络等待期间不能占用连接")
	}
	w := httptest.NewRecorder()
	f2.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	if w.Code != 429 {
		t.Fatal("跨实例并发上限未生效")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := f1.Close(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("截止时间未取消在途请求")
	}
	select {
	case <-exited:
	case <-time.After(5 * time.Second):
		t.Fatal("关闭未传播取消")
	}
	<-requestDone
	if err := f1.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := f1.Pool().Ping(context.Background()); err == nil {
		t.Fatal("自有池未关闭")
	}
	w = httptest.NewRecorder()
	f1.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
	if w.Code != 503 {
		t.Fatal("关闭后仍接受请求")
	}
	lease, err := f2.ModelGate().TryAcquire(context.Background())
	if err != nil || lease == nil {
		t.Fatal("关闭前未释放共享租约")
	}
	lease.Release(context.Background())
}

func TestFoundationPostgresExecutorDrainsBeforePool(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"app.rate-limit.enabled": "false"}, nil)
	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	if !f.Executor().Submit(context.Background(), func(ctx context.Context) error { close(entered); <-release; done <- f.Pool().Ping(ctx); return nil }) {
		t.Fatal("执行器未装配")
	}
	<-entered
	closed := make(chan error, 1)
	go func() { closed <- f.Close(context.Background()) }()
	select {
	case <-closed:
		t.Fatal("任务排空前关闭池")
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal("执行器排空时池已提前关闭")
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	if f.Executor().Submit(context.Background(), taskexec.Task(func(context.Context) error { return nil })) {
		t.Fatal("关闭后仍接受任务")
	}
}

func TestFoundationPostgresHistoryFailureClosesPool(t *testing.T) {
	observer := fixtureFoundation(t, nil, nil)
	files, err := migration.LoadVersioned(os.DirFS("../../../backend/src/main/resources/db/migration"))
	if err != nil {
		t.Fatal(err)
	}
	corrupt := fstest.MapFS{}
	for _, s := range files {
		corrupt[s.Name] = &fstest.MapFile{Data: []byte(s.SQL)}
	}
	corrupt[files[0].Name].Data = append(corrupt[files[0].Name].Data, []byte("\n-- fixture checksum mismatch\n")...)
	p, _ := config.LoadBaselineProperties(config.PropertyOptions{})
	f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-app-failed"), Migrations: corrupt, Schema: "public"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if f != nil || !errors.Is(err, migration.ErrInvalidHistory) {
		t.Fatal("损坏目录未在启动时拒绝")
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		count := -1
		if err := observer.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
			return c.QueryRowContext(context.Background(), "SELECT count(*) FROM pg_stat_activity WHERE application_name='go-app-failed'").Scan(&count)
		}); err != nil {
			t.Fatal(err)
		}
		if count == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("历史拒绝后连接泄露")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestFoundationPostgresFiveRoles(t *testing.T) {
	for _, role := range []string{"combined", "api", "index", "review", "delivery"} {
		t.Run(role, func(t *testing.T) {
			f := fixtureFoundation(t, map[string]string{"app.runtime.role": role}, nil)
			wantPool := 10
			wantHTTP := 200
			if role != "combined" && role != "api" {
				wantPool = 4
				wantHTTP = 404
			}
			if f.Pool().Stats().MaxOpenConnections != wantPool {
				t.Fatal("角色池上限不符")
			}
			w := httptest.NewRecorder()
			f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
			if w.Code != wantHTTP {
				t.Fatal("角色隔离不符")
			}
		})
	}
}

func TestFoundationPostgresManagementAndListeners(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"spring.profiles.active": "monitoring", "app.runtime.role": "index", "app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true"}, nil)
	if f.ManagementHandler() == nil {
		t.Fatal("monitoring未创建独立链")
	}
	business, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	management, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		business.Close()
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- f.Serve(ctx, business, management, 5*time.Second) }()
	client := &http.Client{Timeout: 5 * time.Second}
	for _, test := range []struct {
		port, path string
		want       int
	}{
		{business.Addr().String(), "/api/v1/health", 404},
		{business.Addr().String(), "/actuator/prometheus", 401},
		{management.Addr().String(), "/api/v1/auth/csrf", 403},
		{management.Addr().String(), "/actuator/health", 200},
	} {
		response, err := client.Get("http://" + test.port + test.path)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != test.want {
			t.Fatalf("独立端口权限错误: %s %d", test.path, response.StatusCode)
		}
	}
	r, _ := http.NewRequest("HEAD", "http://"+management.Addr().String()+"/actuator/health", nil)
	response, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 403 {
		t.Fatal("HEAD不在原管理白名单")
	}
	response, err = client.Get("http://" + management.Addr().String() + "/actuator/prometheus")
	if err != nil {
		t.Fatal(err)
	}
	text, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(text), `personalos_go_database_connections{state="max"} 4`) || strings.Contains(string(text), "fixture-password") {
		t.Fatal("指标缺少池上限或泄露凭据")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("双端口未退出")
	}
	for _, listener := range []net.Listener{business, management} {
		rebound, err := net.Listen("tcp", listener.Addr().String())
		if err != nil {
			t.Fatal("监听端口未释放")
		}
		rebound.Close()
	}
	plain := fixtureFoundation(t, nil, nil)
	if plain.ManagementHandler() != nil {
		t.Fatal("未启用monitoring时意外开放管理链")
	}
}

func TestFoundationPostgresManagementDownAndMetrics(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"spring.profiles.active": "monitoring", "app.rate-limit.capacity": "1", "app.rate-limit.period": "1h"}, nil)
	appExec(t, f, "DELETE FROM runtime_rate_bucket")
	for i := 0; i < 2; i++ {
		f.Handler().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	}
	w := httptest.NewRecorder()
	f.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	if !strings.Contains(w.Body.String(), `personalos_rate_limit_acquire_seconds_count{backend="postgres"} 2`) {
		t.Fatal("管理指标未接到实际限流")
	}
	// 只关闭本次fixture自有池，验证已装配的Actuator DOWN映射，不影响任何现有服务。
	if err := f.Pool().Close(); err != nil {
		t.Fatal(err)
	}
	w = httptest.NewRecorder()
	f.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
	if w.Code != 503 || w.Body.String() != `{"status":"DOWN","groups":["liveness","readiness"]}` {
		t.Fatal("真实探测失败未映射Actuator DOWN")
	}
}

func TestFoundationPostgresListenerFailureClosesBoth(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"spring.profiles.active": "monitoring"}, nil)
	business, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	management, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		business.Close()
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- f.Serve(context.Background(), business, management, time.Second) }()
	response, err := (&http.Client{Timeout: 3 * time.Second}).Get("http://" + management.Addr().String() + "/actuator/health")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	business.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("监听失败不能伪装成功")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("另一个监听器未退出")
	}
	if err := f.Pool().Ping(context.Background()); err == nil {
		t.Fatal("监听异常未清理自有池")
	}
	listener, err := net.Listen("tcp", management.Addr().String())
	if err != nil {
		t.Fatal("管理监听器未释放")
	}
	listener.Close()
}

func TestFoundationPostgresServeDeadlineCancelsStream(t *testing.T) {
	entered := make(chan struct{})
	f := fixtureFoundation(t, map[string]string{"app.rate-limit.enabled": "false"}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: fixture\n\n"))
		w.(http.Flusher).Flush()
		close(entered)
		<-r.Context().Done()
	}))
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- f.Serve(ctx, listener, nil, 20*time.Millisecond) }()
	r, err := http.NewRequest("POST", "http://"+listener.Addr().String()+"/api/v1/settings/model/test", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := (&http.Client{Timeout: 5 * time.Second}).Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	<-entered
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal("关闭流的宽限耗尽必须报告期限错误")
		}
	case <-time.After(7 * time.Second):
		t.Fatal("流取消/关闭未完成")
	}
	if err := f.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.Pool().Stats().InUse != 0 {
		t.Fatal("退出流后连接泄漏")
	}
}
