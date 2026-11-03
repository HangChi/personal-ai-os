package app

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
)

// 独立管理链只接收原MonitoringSecurityConfig的两个精确GET，不复用业务匿名白名单。
func newManagementHandler(up func(context.Context) bool, metrics http.Handler, logger *slog.Logger) http.Handler {
	properties := config.ActuatorProperties{Health: true, Prometheus: true, Probes: true, HealthDetails: "never"}
	actuator := newActuatorHandler(properties, func(ctx context.Context) actuatorHealth { return defaultActuatorHealth(up(ctx), properties) }, metrics, logger)
	return newManagementChain(actuator, logger)
}

func newManagementChain(actuator http.Handler, logger *slog.Logger) http.Handler {
	handler := httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		w.Header().Set("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("X-XSS-Protection", "0")
		if r.Method != http.MethodGet {
			w.WriteHeader(http.StatusForbidden)
			return nil
		}
		switch r.URL.Path {
		case "/actuator/health", "/actuator/prometheus":
			actuator.ServeHTTP(w, r)
		default:
			w.WriteHeader(http.StatusForbidden)
		}
		return nil
	}, logger)
	return httpmiddleware.ServletBoundary(httpmiddleware.RequestIDMiddleware(handler))
}

// 标签固定为两个后端；不记录URI/请求/用户/异常。count/sum为无分位数summary，
// 这是最小指标集，不代表完整Micrometer/Actuator运行时迁移。
type foundationMetrics struct {
	models                     *model.Module
	mu                         sync.Mutex
	counts                     [2]uint64
	seconds                    [2]float64
	maxima                     [2]metricWindowMax
	redisErrors                uint64
	extra                      ratelimit.Observer
	clock                      func() time.Time
	started                    time.Time
	httpEnabled, httpHistogram bool
	httpActive                 int64
	httpDropped                uint64
	httpTimers                 map[httpMetricLabels]*httpMetricTimer
}

func (m *foundationMetrics) RecordAcquisition(backend ratelimit.Backend, elapsed time.Duration, failed bool) {
	index := 0
	switch backend {
	case ratelimit.Postgres:
	case ratelimit.Redis:
		index = 1
	default:
		return
	}
	m.mu.Lock()
	now := time.Now()
	if m.clock != nil {
		now = m.clock()
	}
	// Micrometer Timer忽略负样本；错误counter独立于timer。
	if elapsed >= 0 {
		m.maxima[index].record(now, elapsed.Seconds())
		m.counts[index]++
		m.seconds[index] += elapsed.Seconds()
	}
	if backend == ratelimit.Redis && failed {
		m.redisErrors++
	}
	m.mu.Unlock()
	if m.extra != nil {
		m.extra.RecordAcquisition(backend, elapsed, failed)
	}
}

func (m *foundationMetrics) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	counts, seconds, failures := m.counts, m.seconds, m.redisErrors
	m.mu.Unlock()
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	fmt.Fprintln(w, "# HELP personalos_rate_limit_acquire_seconds 共享模型限流获取耗时。")
	fmt.Fprintln(w, "# TYPE personalos_rate_limit_acquire_seconds summary")
	for i, backend := range []string{"postgres", "redis"} {
		fmt.Fprintf(w, "personalos_rate_limit_acquire_seconds_count{backend=%q} %d\n", backend, counts[i])
		fmt.Fprintf(w, "personalos_rate_limit_acquire_seconds_sum{backend=%q} %g\n", backend, seconds[i])
	}
	fmt.Fprintln(w, "# HELP personalos_rate_limit_errors_total 专用Redis限流错误次数。")
	fmt.Fprintln(w, "# TYPE personalos_rate_limit_errors_total counter")
	fmt.Fprintf(w, "personalos_rate_limit_errors_total{backend=\"redis\"} %d\n", failures)
	m.writeHTTPMetrics(w)
	m.writeEmbeddingMetrics(w)
}

// ManagementHandler仅在monitoring有效profile下可用，必须挂到独立监听器。
// 不凭转发头或请求URL猜测本地端口，不将管理权限开放给业务handler。
func (f *Foundation) ManagementHandler() http.Handler { return f.management }

func (f *Foundation) attachManagement(metrics *foundationMetrics, properties config.ActuatorProperties, snapshot func(context.Context) actuatorHealth, logger *slog.Logger) {
	exporter := &foundationMetricExporter{foundationMetrics: metrics, extra: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		pool, executor := f.pool.Stats(), f.executor.Stats()
		fmt.Fprintln(w, "# TYPE personalos_go_database_connections gauge")
		fmt.Fprintf(w, "personalos_go_database_connections{state=\"open\"} %d\npersonalos_go_database_connections{state=\"active\"} %d\npersonalos_go_database_connections{state=\"idle\"} %d\npersonalos_go_database_connections{state=\"max\"} %d\n", pool.OpenConnections, pool.InUse, pool.Idle, pool.MaxOpenConnections)
		fmt.Fprintln(w, "# TYPE personalos_go_post_turn_tasks gauge")
		fmt.Fprintf(w, "personalos_go_post_turn_tasks{state=\"active\"} %d\npersonalos_go_post_turn_tasks{state=\"queued\"} %d\n", executor.Active, executor.Queued)
	})}
	actuator := newActuatorHandler(properties, snapshot, exporter, logger)
	f.management = f.track(metrics.observeHTTP(newManagementChain(actuator, logger)))
}
