package app

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestBootstrapNeverClaimsBusinessReadiness(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, role := range []config.Role{config.Combined, config.API, config.Index, config.Review, config.Delivery} {
		handler := NewHandler(role, logger)
		for _, test := range []struct {
			path   string
			status int
		}{{"/livez", 200}, {"/readyz", 503}} {
			request := httptest.NewRequest(http.MethodGet, test.path, nil)
			writer := httptest.NewRecorder()
			handler.ServeHTTP(writer, request)
			var result Diagnostic
			if err := json.Unmarshal(writer.Body.Bytes(), &result); err != nil {
				t.Fatal(err)
			}
			if writer.Code != test.status || result.Ready || result.Role != role || result.Phase != "bootstrap" {
				t.Fatalf("诊断不能宣告业务就绪：%d，%+v", writer.Code, result)
			}
			if writer.Header().Get(httpmiddleware.RequestIDHeader) == "" || writer.Header().Get("Cache-Control") != "no-store" {
				t.Fatal("诊断缺少链路标识或禁止缓存头")
			}
		}
		writer := httptest.NewRecorder()
		handler.ServeHTTP(writer, httptest.NewRequest(http.MethodGet, "/api/v1/health", nil))
		if writer.Code != 404 {
			t.Fatal("尚未移植的 Java API 不得返回成功")
		}
		if role.Worker() && writer.Body.Len() != 0 {
			t.Fatal("Worker 业务入口应保持空 404")
		}
		if !role.Worker() {
			var result api.Response[any]
			if err := json.Unmarshal(writer.Body.Bytes(), &result); err != nil || result.Code != api.ResourceNotFound {
				t.Fatalf("未知 API 必须明确报错：%s", writer.Body.String())
			}
		}
	}
}

func TestDiagnosticMethods(t *testing.T) {
	handler := NewHandler(config.API, slog.New(slog.NewTextHandler(io.Discard, nil)))
	writer := httptest.NewRecorder()
	handler.ServeHTTP(writer, httptest.NewRequest(http.MethodPost, "/livez", nil))
	if writer.Code != 405 || writer.Header().Get("Allow") != "GET, HEAD" {
		t.Fatal("诊断端点不得接受写请求")
	}
	writer = httptest.NewRecorder()
	handler.ServeHTTP(writer, httptest.NewRequest(http.MethodHead, "/readyz", nil))
	if writer.Code != 503 || writer.Body.Len() != 0 {
		t.Fatal("HEAD 必须保留状态且没有正文")
	}
}
