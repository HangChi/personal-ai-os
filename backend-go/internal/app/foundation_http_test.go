package app

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/health"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/modelgate"
)

type fixtureLimiter struct {
	calls   atomic.Int64
	allowed bool
}

func (l *fixtureLimiter) TryAcquire(context.Context) (bool, error) {
	l.calls.Add(1)
	return l.allowed, nil
}

func fixtureFoundationHandler(t *testing.T, role config.Role, secured, csrf bool, limiter *fixtureLimiter, gate *modelgate.Gate, business http.Handler, actuator ...http.Handler) http.Handler {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service, err := auth.New(auth.Options{Enabled: secured, Username: "admin", Password: "fixture-password", MaxFailures: 5, Lockout: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	store, err := auth.NewMemoryStore(auth.DefaultSessionTimeout)
	if err != nil {
		t.Fatal(err)
	}
	layer, err := auth.NewHTTP(service, store, auth.HTTPOptions{CSRFEnabled: csrf}, logger)
	if err != nil {
		t.Fatal(err)
	}
	probe, err := health.New(health.Options{Application: "fixture", Version: "test", ProbeTimeout: time.Second}, func(context.Context) error { return nil }, func() bool { return false }, logger)
	if err != nil {
		t.Fatal(err)
	}
	if gate == nil {
		gate, err = modelgate.NewLocal(1)
		if err != nil {
			t.Fatal(err)
		}
	}
	var management http.Handler
	if len(actuator) > 0 {
		management = actuator[0]
	}
	return newFoundationHandler(role, layer, limiter, gate, probe, business, management, logger)
}

func TestFoundationHTTPProtectionOrder(t *testing.T) {
	for _, test := range []struct {
		name             string
		rate, csrf, full bool
		want             int
		calls            int64
	}{
		{"rate before csrf", false, true, false, 429, 1},
		{"gate before csrf", true, true, true, 429, 1},
		{"csrf before authentication", true, true, false, 403, 1},
		{"authentication after protection", true, false, false, 401, 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			limiter := &fixtureLimiter{allowed: test.rate}
			gate, _ := modelgate.NewLocal(1)
			if test.full {
				lease, _ := gate.TryAcquire(context.Background())
				defer lease.Release(context.Background())
			}
			h := fixtureFoundationHandler(t, config.API, true, test.csrf, limiter, gate, nil)
			w := httptest.NewRecorder()
			r := httptest.NewRequest(http.MethodPost, "/api/v1/settings/model/test", nil)
			r.Header.Set(httpmiddleware.RequestIDHeader, "fixture-order")
			h.ServeHTTP(w, r)
			if w.Code != test.want || limiter.calls.Load() != test.calls || w.Header().Get(httpmiddleware.RequestIDHeader) != "fixture-order" {
				t.Fatalf("链顺序错误: %d %s", w.Code, w.Body.String())
			}
			if !test.full {
				lease, err := gate.TryAcquire(context.Background())
				if err != nil || lease == nil {
					t.Fatal("安全拒绝后并发许可泄露")
				}
				lease.Release(context.Background())
			}
		})
	}
}

func TestFoundationHTTPWorkerIsolation(t *testing.T) {
	for _, role := range []config.Role{config.Index, config.Review, config.Delivery} {
		limiter := &fixtureLimiter{}
		h := fixtureFoundationHandler(t, role, true, true, limiter, nil, http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("Worker执行业务") }))
		for _, path := range []string{"/api/v1/health", "/api/v1/auth/csrf", "/api/v1/settings/model/test", "/livez", "/readyz"} {
			w := httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, path, nil))
			if w.Code != 404 || w.Body.Len() != 0 || len(w.Header().Values("Set-Cookie")) != 0 || limiter.calls.Load() != 0 {
				t.Fatal("Worker未在依赖访问前隔离")
			}
		}
	}
}

func TestFoundationHTTPRoutesAndErrorBoundary(t *testing.T) {
	limiter := &fixtureLimiter{allowed: true}
	business := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/panic" {
			panic("fixture-private-error")
		}
		if r.URL.Path == "/stream" {
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: fixture\n\n"))
			w.(http.Flusher).Flush()
			panic("fixture-private-error")
		}
		w.WriteHeader(418)
	})
	h := fixtureFoundationHandler(t, config.Combined, false, false, limiter, nil, business)
	for _, test := range []struct {
		path string
		want int
	}{
		{"/api/v1/health", 200}, {"/api/v1/auth/me", 200}, {"/api/v1/auth/csrf", 200}, {"/readyz", 503}, {"/livez", 200}, {"/panic", 500}, {"/other", 418}, {"/actuator/prometheus", 404},
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", test.path, nil))
		if w.Code != test.want || strings.Contains(w.Body.String(), "fixture-private-error") {
			t.Fatalf("路由或边界错误: %s %d %s", test.path, w.Code, w.Body.String())
		}
	}
	func() {
		defer func() {
			if recover() != http.ErrAbortHandler {
				t.Error("已提交SSE异常必须中止")
			}
		}()
		w := httptest.NewRecorder()
		defer func() {
			if w.Body.String() != "data: fixture\n\n" {
				t.Error("SSE被JSON污染")
			}
		}()
		h.ServeHTTP(w, httptest.NewRequest("GET", "/stream", nil))
	}()
	plain := fixtureFoundationHandler(t, config.API, false, false, limiter, nil, nil)
	w := httptest.NewRecorder()
	plain.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/tasks", nil))
	if w.Code != 404 {
		t.Fatal("未实现业务不能返回假成功")
	}
}
