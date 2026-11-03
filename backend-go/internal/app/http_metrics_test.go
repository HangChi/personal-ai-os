package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestHTTPMetricsRecordAtStreamTermination(t *testing.T) {
	m := &foundationMetrics{httpEnabled: true, httpHistogram: true, started: time.Now()}
	opened := make(chan struct{})
	release := make(chan struct{})
	done := make(chan struct{})
	handler := m.observeHTTP(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpmiddleware.SetRequestRoute(r.Context(), "/fixture/{id}")
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "data: fixture\n\n")
		w.(http.Flusher).Flush()
		close(opened)
		<-release
	}))
	go func() {
		defer close(done)
		handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/fixture/private-id?token=private", nil))
	}()
	<-opened
	m.mu.Lock()
	active, timers := m.httpActive, len(m.httpTimers)
	m.mu.Unlock()
	if active != 1 || timers != 0 {
		t.Fatal("流未结束便累计完成计时", active, timers)
	}
	close(release)
	<-done
	value, found, err := m.metricSnapshot("/"+httpRequestMetric, url.Values{"tag": {"uri:/fixture/{id}", "status:200"}})
	if err != nil || !found {
		t.Fatal("流结束未计量", err)
	}
	metric := value.(actuatorMetric)
	if metric.Measurements[0].Value != 1 || metric.Measurements[1].Value <= 0 {
		t.Fatal(metric)
	}
	w := httptest.NewRecorder()
	m.ServeHTTP(w, httptest.NewRequest("GET", "/", nil))
	if !strings.Contains(w.Body.String(), "http_server_requests_seconds_bucket{") || !strings.Contains(w.Body.String(), "personalos_go_http_active_requests 0") || strings.Contains(w.Body.String(), "private") {
		t.Fatal("指标错误或含高基数私有信息")
	}
}

func TestHTTPMetricsUnknownPathsAndCardinality(t *testing.T) {
	m := &foundationMetrics{httpEnabled: true}
	h := m.observeHTTP(http.NotFoundHandler())
	var wg sync.WaitGroup
	for i := 0; i < 1000; i++ {
		wg.Go(func() {
			h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", fmt.Sprintf("/untrusted/%d?secret=private", i), nil))
		})
	}
	wg.Wait()
	m.mu.Lock()
	if len(m.httpTimers) != 1 || m.httpActive != 0 {
		t.Error("未知路径创建无界标签")
	}
	m.mu.Unlock()
	value, found, err := m.metricSnapshot("/"+httpRequestMetric, url.Values{"tag": {"uri:NOT_FOUND"}})
	if err != nil || !found || value.(actuatorMetric).Measurements[0].Value != 1000 {
		t.Fatal("并发计量丢失", err)
	}
	for i := 0; i < 300; i++ {
		m.observeHTTP(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			httpmiddleware.SetRequestRoute(r.Context(), fmt.Sprintf("/route-%d", i))
			w.WriteHeader(204)
		})).ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/", nil))
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.httpTimers) != 256 || m.httpDropped != 45 {
		t.Fatal("指标基数未受限", len(m.httpTimers), m.httpDropped)
	}
}

func TestHTTPMetricsCancellationAndAbort(t *testing.T) {
	for _, mode := range []string{"cancel", "panic", "abort"} {
		t.Run(mode, func(t *testing.T) {
			m := &foundationMetrics{httpEnabled: true}
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			h := m.observeHTTP(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if mode == "panic" {
					panic("private")
				}
				w.WriteHeader(200)
				if mode == "abort" {
					panic(http.ErrAbortHandler)
				}
				<-r.Context().Done()
			}))
			func() {
				defer func() { _ = recover() }()
				h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/stream", nil).WithContext(ctx))
			}()
			m.mu.Lock()
			defer m.mu.Unlock()
			if m.httpActive != 0 || len(m.httpTimers) != 1 {
				t.Fatal("终止未完成计量")
			}
			for labels, timer := range m.httpTimers {
				if timer.Count != 1 || mode == "panic" && labels.Status != "500" || mode != "cancel" && labels.Exception != "RequestAborted" {
					t.Fatal("终止重复/状态错误", labels, timer)
				}
			}
		})
	}
}

func TestHTTPMetricLabelsAndHistogramJavaOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java-http-metrics.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string    `json:"baseline_commit"`
		Bounds   []float64 `json:"bounds_seconds"`
		Cases    []struct {
			Method, Route string
			Status        int
			Tags          map[string]string
		}
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" {
		t.Fatal("错误Java基线")
	}
	got := httpHistogramBounds()
	if len(got) != len(fixture.Bounds) {
		t.Fatal("桶数错误", len(got), len(fixture.Bounds))
	}
	for i, bound := range got {
		if bound != fixture.Bounds[i] {
			t.Fatal("桶边界不等价", i, bound, fixture.Bounds[i])
		}
	}
	for _, row := range fixture.Cases {
		tags := observationLabels(httpmiddleware.HTTPObservation{Method: row.Method, Route: row.Route, Status: row.Status}).values()
		for key, want := range row.Tags {
			if tags[key] != want {
				t.Fatal("原Spring观测标签不等价", row, key, tags[key], want)
			}
		}
	}
}
