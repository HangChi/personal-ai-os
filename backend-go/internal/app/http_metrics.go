package app

import (
	"fmt"
	"net/http"
	"net/url"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

const httpRequestMetric = "http.server.requests"

type httpMetricLabels struct{ Exception, Method, Outcome, Status, URI string }
type httpMetricTimer struct {
	Count   uint64
	Total   float64
	Maximum metricWindowMax
	Buckets []uint64
}

// 与固定Micrometer Timer的1ms–30s默认桶边界一致；算法见Apache-2.0的
// PercentileHistogramBuckets.java，保留min/max端点。不使用任意用户配置扩展桶基数。
func httpHistogramBounds() []float64 {
	values := []float64{.001, 30}
	for exp := 2; exp <= 36; exp += 2 {
		current := int64(1) << exp
		delta := current / 3
		next := (current << 2) - delta
		for ; current < next; current += delta {
			if current >= 1_000_000 && current <= 30_000_000_000 {
				values = append(values, float64(current)/1e9)
			}
		}
	}
	slices.Sort(values)
	return slices.Compact(values)
}

func observationLabels(sample httpmiddleware.HTTPObservation) httpMetricLabels {
	labels := httpMetricLabels{Exception: "none", Method: sample.Method, Status: strconv.Itoa(sample.Status), URI: sample.Route, Outcome: "UNKNOWN"}
	if !slices.Contains([]string{"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}, labels.Method) {
		labels.Method = "UNKNOWN"
	}
	if labels.URI == "" {
		switch {
		case sample.Status == 404:
			labels.URI = "NOT_FOUND"
		case sample.Status >= 300 && sample.Status < 400:
			labels.URI = "REDIRECTION"
		default:
			labels.URI = "UNKNOWN"
		}
	}
	switch sample.Status / 100 {
	case 1:
		labels.Outcome = "INFORMATIONAL"
	case 2:
		labels.Outcome = "SUCCESS"
	case 3:
		labels.Outcome = "REDIRECTION"
	case 4:
		labels.Outcome = "CLIENT_ERROR"
	case 5:
		labels.Outcome = "SERVER_ERROR"
	}
	if sample.Aborted {
		labels.Exception = "RequestAborted"
	}
	return labels
}

func foundationRoute(path string) string {
	switch path {
	case "/api/v1/health", "/api/v1/auth/me", "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout", "/livez", "/readyz", "/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus":
		return path
	}
	if strings.HasPrefix(path, "/actuator/metrics/") {
		return "/actuator/metrics/{requiredMetricName}"
	}
	if strings.HasPrefix(path, "/actuator/health/") {
		return "/actuator/health/{*path}"
	}
	return ""
}

func (m *foundationMetrics) observeHTTP(next http.Handler) http.Handler {
	if !m.httpEnabled {
		return next
	}
	return httpmiddleware.ObserveHTTP(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if route := foundationRoute(r.URL.Path); route != "" {
			httpmiddleware.SetRequestRoute(r.Context(), route)
		}
		next.ServeHTTP(w, r)
	}), func() { m.mu.Lock(); m.httpActive++; m.mu.Unlock() }, func(sample httpmiddleware.HTTPObservation) {
		labels := observationLabels(sample)
		m.mu.Lock()
		defer m.mu.Unlock()
		m.httpActive--
		if m.httpTimers == nil {
			m.httpTimers = map[httpMetricLabels]*httpMetricTimer{}
		}
		if _, found := m.httpTimers[labels]; !found && len(m.httpTimers) >= 256 {
			m.httpDropped++
			return
		}
		timer := m.httpTimers[labels]
		if timer == nil {
			timer = &httpMetricTimer{Buckets: make([]uint64, len(httpHistogramBounds()))}
			m.httpTimers[labels] = timer
		}
		timer.Count++
		timer.Total += sample.Elapsed.Seconds()
		timer.Maximum.record(time.Now(), sample.Elapsed.Seconds())
		if m.httpHistogram {
			for i, bound := range httpHistogramBounds() {
				if sample.Elapsed.Seconds() <= bound {
					timer.Buckets[i]++
				}
			}
		}
	})
}

func (labels httpMetricLabels) values() map[string]string {
	return map[string]string{"exception": labels.Exception, "method": labels.Method, "outcome": labels.Outcome, "status": labels.Status, "uri": labels.URI}
}

func (m *foundationMetrics) httpMetricSnapshotLocked(query url.Values) (any, bool, error) {
	result := actuatorMetric{Name: httpRequestMetric, BaseUnit: "seconds", AvailableTags: []actuatorMetricTag{}}
	filters := map[string]string{}
	for _, entry := range query["tag"] {
		key, value, ok := strings.Cut(entry, ":")
		if !ok {
			return nil, false, fmt.Errorf("指标标签格式无效")
		}
		if old, ok := filters[key]; ok && old != value {
			return nil, false, nil
		}
		filters[key] = value
	}
	tags := map[string]map[string]bool{}
	var count, total, maximum float64
	found := false
	for labels, timer := range m.httpTimers {
		values := labels.values()
		match := true
		for key, value := range filters {
			if values[key] != value {
				match = false
				break
			}
		}
		if !match {
			continue
		}
		found = true
		count += float64(timer.Count)
		total += timer.Total
		timer.Maximum.rotate(time.Now())
		maximum = max(maximum, timer.Maximum.buckets[timer.Maximum.current])
		for key, value := range values {
			if _, filtered := filters[key]; filtered {
				continue
			}
			if tags[key] == nil {
				tags[key] = map[string]bool{}
			}
			tags[key][value] = true
		}
	}
	if !found {
		return nil, false, nil
	}
	for _, key := range []string{"exception", "method", "outcome", "status", "uri"} {
		if values, ok := tags[key]; ok {
			items := []string{}
			for value := range values {
				items = append(items, value)
			}
			slices.Sort(items)
			result.AvailableTags = append(result.AvailableTags, actuatorMetricTag{key, items})
		}
	}
	result.Measurements = []actuatorMeasurement{{"COUNT", count}, {"TOTAL_TIME", total}, {"MAX", maximum}}
	return result, true, nil
}

func (m *foundationMetrics) runtimeMetricLocked(name string, query url.Values) (any, bool, error) {
	if m.started.IsZero() {
		return nil, false, nil
	}
	value, unit := float64(0), ""
	switch name {
	case "go.goroutines":
		value = float64(runtime.NumGoroutine())
	case "go.memory.used":
		var mem runtime.MemStats
		runtime.ReadMemStats(&mem)
		value = float64(mem.Alloc)
		unit = "bytes"
	case "process.uptime":
		value = time.Since(m.started).Seconds()
		unit = "seconds"
	default:
		return nil, false, nil
	}
	if len(query["tag"]) > 0 {
		return nil, false, nil
	}
	return actuatorMetric{Name: name, BaseUnit: unit, Measurements: []actuatorMeasurement{{"VALUE", value}}, AvailableTags: []actuatorMetricTag{}}, true, nil
}

func (m *foundationMetrics) writeHTTPMetrics(w http.ResponseWriter) {
	m.mu.Lock()
	started, enabled, histogram, active, dropped := m.started, m.httpEnabled, m.httpHistogram, m.httpActive, m.httpDropped
	timers := make(map[httpMetricLabels]httpMetricTimer, len(m.httpTimers))
	for labels, timer := range m.httpTimers {
		timer.Maximum.rotate(time.Now())
		snapshot := *timer
		snapshot.Buckets = append([]uint64(nil), timer.Buckets...)
		timers[labels] = snapshot
	}
	m.mu.Unlock()
	// 导出使用快照；慢采集客户端不能持锁阻塞正常请求完成或关闭。
	if !started.IsZero() {
		var mem runtime.MemStats
		runtime.ReadMemStats(&mem)
		fmt.Fprintln(w, "# TYPE go_goroutines gauge")
		fmt.Fprintln(w, "go_goroutines", runtime.NumGoroutine())
		fmt.Fprintln(w, "# TYPE go_memory_used_bytes gauge")
		fmt.Fprintln(w, "go_memory_used_bytes", mem.Alloc)
		fmt.Fprintln(w, "# TYPE process_uptime_seconds gauge")
		fmt.Fprintln(w, "process_uptime_seconds", time.Since(started).Seconds())
	}
	if !enabled {
		return
	}
	fmt.Fprintln(w, "# TYPE personalos_go_http_active_requests gauge")
	fmt.Fprintln(w, "personalos_go_http_active_requests", active)
	fmt.Fprintln(w, "# TYPE personalos_go_http_metric_dropped_total counter")
	fmt.Fprintln(w, "personalos_go_http_metric_dropped_total", dropped)
	kind := "summary"
	if histogram {
		kind = "histogram"
	}
	fmt.Fprintln(w, "# TYPE http_server_requests_seconds", kind)
	keys := make([]httpMetricLabels, 0, len(timers))
	for labels := range timers {
		keys = append(keys, labels)
	}
	slices.SortFunc(keys, func(a, b httpMetricLabels) int { return strings.Compare(fmt.Sprint(a), fmt.Sprint(b)) })
	for _, labels := range keys {
		timer := timers[labels]
		tags := fmt.Sprintf("exception=%q,method=%q,outcome=%q,status=%q,uri=%q", labels.Exception, labels.Method, labels.Outcome, labels.Status, labels.URI)
		fmt.Fprintf(w, "http_server_requests_seconds_count{%s} %d\nhttp_server_requests_seconds_sum{%s} %g\n", tags, timer.Count, tags, timer.Total)
		fmt.Fprintf(w, "http_server_requests_seconds_max{%s} %g\n", tags, timer.Maximum.buckets[timer.Maximum.current])
		if histogram {
			for i, bound := range httpHistogramBounds() {
				fmt.Fprintf(w, "http_server_requests_seconds_bucket{%s,le=%q} %d\n", tags, strconv.FormatFloat(bound, 'g', -1, 64), timer.Buckets[i])
			}
			fmt.Fprintf(w, "http_server_requests_seconds_bucket{%s,le=\"+Inf\"} %d\n", tags, timer.Count)
		}
	}
}
