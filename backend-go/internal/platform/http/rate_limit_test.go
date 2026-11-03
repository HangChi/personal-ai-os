package httpmiddleware

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type limiterFunc func(context.Context) (bool, error)

func (function limiterFunc) TryAcquire(ctx context.Context) (bool, error) { return function(ctx) }

func TestRateLimitFilterJavaCasesAndEncodedPaths(t *testing.T) {
	for _, test := range []struct {
		method  string
		path    string
		guarded bool
	}{
		{"POST", "/api/v1/chat/sessions/abc/stream", true},
		{"POST", "/api/v1/knowledge/documents", true},
		{"POST", "/api/v1/chat/%75ploads", true},
		{"POST", "/api/v1/chat/%2575ploads", false},
		{"POST", "/api/v1/knowledge/search?debug=private-query", true},
		{"PUT", "/api/v1/knowledge/documents/123", true},
		{"POST", "/api/v1/tasks", false},
		{"GET", "/api/v1/chat/sessions", false},
		{"GET", "/api/v1/knowledge/search", false},
		{"HEAD", "/api/v1/chat/uploads", false},
		{"OPTIONS", "/api/v1/chat/uploads", false},
	} {
		t.Run(test.method+" "+test.path, func(t *testing.T) {
			var output bytes.Buffer
			logger := slog.New(slog.NewJSONHandler(&output, nil))
			calls, downstream := 0, 0
			limiter := limiterFunc(func(ctx context.Context) (bool, error) {
				calls++
				if RequestID(ctx) != "rate-test-123" {
					t.Fatal("请求标识应先于限流传播")
				}
				return false, nil
			})
			handler := RequestIDMiddleware(RateLimitMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				downstream++
				w.WriteHeader(http.StatusCreated)
			}), limiter, logger))
			request := httptest.NewRequest(test.method, test.path, strings.NewReader("private-body"))
			request.Header.Set(RequestIDHeader, "rate-test-123")
			request.Header.Set("Authorization", "Bearer private-token")
			request.Header.Set("Accept", "text/event-stream")
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Header().Get(RequestIDHeader) != "rate-test-123" {
				t.Fatal("拒绝也应回显请求标识")
			}
			if test.guarded {
				if response.Code != http.StatusTooManyRequests || calls != 1 || downstream != 0 {
					t.Fatalf("应拒绝且只消费一次：%d %d %d", response.Code, calls, downstream)
				}
				if response.Header().Get("Content-Type") != "application/json;charset=UTF-8" {
					t.Fatal("限流响应应为 JSON")
				}
				var envelope api.Response[any]
				if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
					t.Fatal(err)
				}
				if envelope.Code != api.RateLimited || envelope.Message != "请求过于频繁，请稍后再试" || envelope.Data != nil || envelope.Timestamp.IsZero() || envelope.Timestamp.Location() != time.UTC {
					t.Fatalf("Java 限流信封不一致：%+v", envelope)
				}
				if !strings.Contains(output.String(), "模型调用被限流") {
					t.Fatal("拒绝应有可定位日志")
				}
			} else if response.Code != http.StatusCreated || calls != 0 || downstream != 1 || output.Len() != 0 {
				t.Fatalf("普通路由不能消耗模型额度：%d %d %d %s", response.Code, calls, downstream, output.String())
			}
			for _, secret := range []string{"private-query", "private-body", "private-token"} {
				if strings.Contains(output.String(), secret) || strings.Contains(response.Body.String(), secret) {
					t.Fatal("请求正文、query 或凭据不得进入限流日志/响应")
				}
			}
		})
	}
}

func TestRateLimitAllowsWithoutTouchingSuccessfulResponse(t *testing.T) {
	calls := 0
	var output bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&output, nil))
	limiter := limiterFunc(func(context.Context) (bool, error) { calls++; return true, nil })
	handler := RateLimitMiddleware(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.URL.RawQuery != "x=1" {
			t.Fatal("中间件不能改写请求")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte("event: ready\n\ndata: ok\n\n"))
	}), limiter, logger)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("POST", "/api/v1/chat/sessions/a/stream?x=1", nil))
	if response.Code != http.StatusAccepted || response.Header().Get("Content-Type") != "text/event-stream" || response.Body.String() != "event: ready\n\ndata: ok\n\n" || calls != 1 || output.Len() != 0 {
		t.Fatal("放行不能覆盖下游流响应或重复消费")
	}
}

func TestRateLimitBackendErrorDoesNotRunDownstreamOrExposeCause(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	limiter := limiterFunc(func(context.Context) (bool, error) {
		return true, errors.New("postgres://private-credentials@private-host")
	})
	handler := RateLimitMiddleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("后端错误不能放行")
	}), limiter, logger)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("POST", "/api/v1/knowledge/ask", nil))
	if response.Code != http.StatusInternalServerError || !strings.Contains(response.Body.String(), `"code":50000`) || strings.Contains(response.Body.String()+output.String(), "private-") {
		t.Fatalf("后端错误应脱敏且与额度耗尽区分：%d %s %s", response.Code, response.Body.String(), output.String())
	}
}
