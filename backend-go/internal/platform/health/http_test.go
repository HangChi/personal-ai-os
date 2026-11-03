package health

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/auth"
)

func TestJavaHealthHTTPReportAndMethods(t *testing.T) {
	for _, databaseDown := range []bool{false, true} {
		calls := 0
		service, _ := New(testOptions(), func(context.Context) error {
			calls++
			if databaseDown {
				return errors.New("unavailable")
			}
			return nil
		}, func() bool { return !databaseDown }, slog.Default())
		handler := service.Handler()
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", "/api/v1/health", nil))
		var body api.Response[Status]
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if response.Code != 200 || body.Code != api.Success || body.Timestamp.IsZero() || body.Data.Timestamp.IsZero() || calls != 1 {
			t.Fatal("健康状态报告必须返回 HTTP 200 统一信封")
		}
		if databaseDown && body.Data.Status != "DOWN" {
			t.Fatal("数据库 DOWN 被隐藏")
		}
		response = httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("HEAD", "/api/v1/health", nil))
		if response.Code != 200 || response.Body.Len() != 0 || calls != 2 || response.Header().Get("Content-Type") != "application/json" {
			t.Fatal("HEAD 未保持状态探测/无正文")
		}
		response = httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("POST", "/api/v1/health", nil))
		if response.Code != 405 || response.Header().Get("Allow") != "" || calls != 2 {
			t.Fatal("错误方法不能探测依赖")
		}
	}
}

func TestHealthSecurityChainRemainsPublic(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service, _ := New(testOptions(), func(context.Context) error { return nil }, func() bool { return false }, logger)
	options := auth.DefaultOptions()
	options.Enabled, options.Password = true, "synthetic-health-password"
	credentials, err := auth.New(options)
	if err != nil {
		t.Fatal(err)
	}
	store, _ := auth.NewMemoryStore(testOptions().ProbeTimeout)
	security, err := auth.NewHTTP(credentials, store, auth.HTTPOptions{CSRFEnabled: true}, logger)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.Handle("/api/v1/health", service.Handler())
	response := httptest.NewRecorder()
	security.Handler(mux).ServeHTTP(response, httptest.NewRequest("GET", "/api/v1/health", nil))
	if response.Code != 200 || len(response.Result().Cookies()) != 0 {
		t.Fatal("匿名健康报告被认证阻断或创建会话")
	}
}
