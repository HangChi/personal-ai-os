package app

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/ratelimit"
)

func TestManagementOnlyAllowsExactGET(t *testing.T) {
	var probes, metrics atomic.Int64
	h := newManagementHandler(func(context.Context) bool { probes.Add(1); return true }, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { metrics.Add(1); w.Write([]byte("fixture_metric 1\n")) }), slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, test := range []struct {
		method, path string
		want         int
	}{
		{"GET", "/actuator/health", 200}, {"GET", "/actuator/prometheus", 200},
		{"HEAD", "/actuator/health", 403}, {"HEAD", "/actuator/prometheus", 403},
		{"POST", "/actuator/health", 403}, {"OPTIONS", "/actuator/prometheus", 403},
		{"GET", "/api/v1/auth/csrf", 403}, {"GET", "/api/v1/health", 403},
		{"GET", "/actuator/health/", 403}, {"GET", "/actuator/health/liveness", 403}, {"GET", "/actuator/env", 403},
		{"GET", "/actuator//health", 400},
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(test.method, test.path, nil))
		if w.Code != test.want {
			t.Fatalf("管理白名单错误: %s %s %d", test.method, test.path, w.Code)
		}
		if len(w.Header().Values("Set-Cookie")) != 0 || test.path != "/actuator//health" && w.Header().Get("Cache-Control") == "" {
			t.Fatal("管理入口不应创建业务会话")
		}
		if test.path == "/actuator/health" && test.want == 200 && w.Body.String() != `{"status":"UP","groups":["liveness","readiness"]}` {
			t.Fatal("Actuator不能套业务信封或泄露组件")
		}
	}
	if probes.Load() != 1 || metrics.Load() != 1 {
		t.Fatal("被拒请求不能探测或导出指标")
	}
	down := newManagementHandler(func(context.Context) bool { return false }, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}), slog.New(slog.NewTextHandler(io.Discard, nil)))
	w := httptest.NewRecorder()
	down.ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
	if w.Code != 503 || w.Body.String() != `{"status":"DOWN","groups":["liveness","readiness"]}` {
		t.Fatal("Actuator DOWN应503且无内部详情")
	}
}

func TestFoundationRateMetricsConcurrentAndBounded(t *testing.T) {
	m := &foundationMetrics{}
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Go(func() {
			m.RecordAcquisition(ratelimit.Postgres, time.Millisecond, false)
			m.RecordAcquisition(ratelimit.Redis, 2*time.Millisecond, true)
		})
	}
	wg.Wait()
	m.RecordAcquisition(ratelimit.Backend("untrusted-label"), time.Second, true)
	w := httptest.NewRecorder()
	m.ServeHTTP(w, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	for _, want := range []string{
		`personalos_rate_limit_acquire_seconds_count{backend="postgres"} 100`,
		`personalos_rate_limit_acquire_seconds_count{backend="redis"} 100`,
		`personalos_rate_limit_errors_total{backend="redis"} 100`,
		"# TYPE personalos_rate_limit_acquire_seconds summary",
	} {
		if !strings.Contains(w.Body.String(), want) {
			t.Fatalf("缺少原限流指标: %s", want)
		}
	}
	if strings.Contains(w.Body.String(), "untrusted-label") {
		t.Fatal("不能创建任意标签")
	}
	if !strings.Contains(w.Header().Get("Content-Type"), "text/plain") {
		t.Fatal("指标不是Prometheus文本")
	}
}
