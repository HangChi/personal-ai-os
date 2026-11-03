//go:build integration

package app

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFoundationPostgresActuatorActualRateMetrics(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"app.rate-limit.capacity": "1", "app.rate-limit.period": "1h"}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	appExec(t, f, "DELETE FROM runtime_rate_bucket")
	for i, want := range []int{204, 429} {
		w := httptest.NewRecorder()
		f.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
		if w.Code != want {
			t.Fatal("真实共享桶请求没有按预算执行", i, w.Code)
		}
	}
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/metrics/personalos.rate.limit.acquire?tag=backend:postgres", nil))
	var metric actuatorMetric
	if err := json.Unmarshal(w.Body.Bytes(), &metric); err != nil {
		t.Fatal(err)
	}
	if w.Code != 200 || w.Header().Get("Content-Type") != actuatorMediaType || metric.Name != rateAcquireMetric || metric.BaseUnit != "seconds" || len(metric.Measurements) != 3 || len(metric.AvailableTags) != 0 {
		t.Fatal("实际指标未接到业务Actuator", w.Code, w.Body.String())
	}
	if metric.Measurements[0].Value != 2 || metric.Measurements[1].Value <= 0 || metric.Measurements[2].Value <= 0 || metric.Measurements[2].Value > metric.Measurements[1].Value {
		t.Fatal("采样未记录实际允许/拒绝请求的次数及耗时", metric.Measurements)
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/metrics/personalos.rate.limit.errors", nil))
	if w.Code != 404 || w.Body.Len() != 0 {
		t.Fatal("未启用Redis不能伪造Redis错误counter", w.Code)
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	if w.Code != 404 {
		t.Fatal("默认业务端口不能暴露Prometheus")
	}
}

func TestFoundationPostgresActualHTTPAndRuntimeMetrics(t *testing.T) {
	f := fixtureFoundation(t, map[string]string{"app.rate-limit.enabled": "false", "management.metrics.distribution.percentiles-histogram.http.server.requests": "true", "management.endpoints.web.exposure.include": "health,metrics,prometheus"}, nil)
	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
		if w.Code != 200 {
			t.Fatal("真实健康失败")
		}
	}
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/metrics/http.server.requests?tag=uri:/api/v1/health", nil))
	var metric actuatorMetric
	if err := json.Unmarshal(w.Body.Bytes(), &metric); err != nil {
		t.Fatal(err)
	}
	if w.Code != 200 || len(metric.Measurements) != 3 || metric.Measurements[0].Value != 2 || metric.Measurements[1].Value <= 0 {
		t.Fatal("真实请求未计量", w.Body.String())
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/metrics/go.goroutines", nil))
	if err := json.Unmarshal(w.Body.Bytes(), &metric); err != nil {
		t.Fatal(err)
	}
	if w.Code != 200 || len(metric.Measurements) != 1 || metric.Measurements[0].Value < 1 {
		t.Fatal("未采集实际Go运行时")
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), "http_server_requests_seconds_bucket{") || !strings.Contains(w.Body.String(), "go_memory_used_bytes ") {
		t.Fatal("原histogram配置未装配")
	}
}
