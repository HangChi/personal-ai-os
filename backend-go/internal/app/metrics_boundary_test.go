package app

import (
	"context"
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/knowledge"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
	"testing"
	"time"
)

func TestMetricEmptySourcesAndActiveGaugeFilters(t *testing.T) {
	m := &foundationMetrics{}
	if m.chatMetricNames() != nil {
		t.Fatal("absent chat names")
	}
	m.writeChatMetrics(httptest.NewRecorder())
	if _, ok, e := m.chatMetric("absent", nil); ok || e != nil {
		t.Fatal(ok, e)
	}
	engine := &chat.Engine{}
	engine.Metrics.Active.Store(2)
	m.chat = &chat.Module{Engine: engine}
	for i := 0; i < 2; i++ {
		engine.Metrics.Measurements.Duration("provider", "model", []string{"ok", "error"}[i], time.Second)
	}
	if len(m.chatMetricNames()) != 2 {
		t.Fatal(m.chatMetricNames())
	}
	if v, ok, e := m.chatMetric("personalos.chat.streams.active", nil); e != nil || !ok || v.(actuatorMetric).Measurements[0].Value != 2 {
		t.Fatal(v, ok, e)
	}
	if _, ok, e := m.chatMetric("personalos.chat.streams.active", url.Values{"tag": {"model:x"}}); e != nil || ok {
		t.Fatal(ok, e)
	}
	if v, ok, e := m.chatMetric("personalos.chat.duration", nil); e != nil || !ok || v.(actuatorMetric).Measurements[0].Value != 2 {
		t.Fatal(v, ok, e)
	}
	stats := knowledge.NewStats()
	stats.Count("custom", "label")
	stats.Duration("custom", "ok", time.Second)
	stats.Duration("custom", "error", 2*time.Second)
	m.p6 = &knowledge.Module{Stats: stats}
	if len(m.knowledgeMetricNames()) == 0 {
		t.Fatal("missing names")
	}
	for _, query := range []url.Values{{"tag": {"bad"}}, {"tag": {"result:ok", "result:error"}}, {"tag": {"unknown:no"}}} {
		_, ok, e := m.knowledgeMetric(context.Background(), "personalos.knowledge.custom", query)
		if ok || (query["tag"][0] == "bad" && e == nil) {
			t.Fatal(query, ok, e)
		}
	}
	w := httptest.NewRecorder()
	m.writeKnowledgeMetrics(w, context.Background())
	if !strings.Contains(w.Body.String(), "summary") {
		t.Fatal(w.Body.String())
	}
}
func TestHTTPObservationStatusesAndRuntimeMetricsBoundaries(t *testing.T) {
	for _, status := range []int{100, 204, 302, 404, 503, 700} {
		v := observationLabels(web.HTTPObservation{Method: "SYNTHETIC", Status: status})
		if v.Method != "UNKNOWN" || v.URI == "" {
			t.Fatal(v)
		}
	}
	if foundationRoute("/actuator/metrics/name") != "/actuator/metrics/{requiredMetricName}" || foundationRoute("/actuator/health/db") != "/actuator/health/{*path}" {
		t.Fatal("route")
	}
	m := &foundationMetrics{}
	if _, ok, e := m.runtimeMetricLocked("go.goroutines", nil); ok || e != nil {
		t.Fatal(ok, e)
	}
	m.started = time.Now()
	for _, name := range []string{"go.goroutines", "go.memory.used", "process.uptime", "unknown"} {
		if _, ok, e := m.runtimeMetricLocked(name, nil); e != nil || ok != (name != "unknown") {
			t.Fatal(name, ok, e)
		}
		if _, ok, e := m.runtimeMetricLocked(name, url.Values{"tag": {"bad"}}); e != nil || ok {
			t.Fatal(ok, e)
		}
	}
}

func TestKnowledgeCounterTimerAndDatabaseGaugeQueries(t *testing.T) {
	stats := knowledge.NewStats()
	stats.Count("rag.results", "READY")
	stats.Count("rag.results", "UNVERIFIED")
	stats.Duration("rag.stage.recall", "OK", time.Second)
	m := &foundationMetrics{p6: &knowledge.Module{Stats: stats}}
	for _, name := range []string{"personalos.knowledge.rag.results", "personalos.knowledge.rag.stage", "personalos.knowledge.jobs.active"} {
		v, ok, e := m.knowledgeMetric(context.Background(), name, nil)
		if e != nil || !ok || len(v.(actuatorMetric).Measurements) == 0 {
			t.Fatal(v, ok, e)
		}
	}
	for _, query := range []url.Values{{"tag": {"status:READY"}}, {"tag": {"result:OK"}}} {
		name := "personalos.knowledge.rag.results"
		if query["tag"][0] == "result:OK" {
			name = "personalos.knowledge.rag.stage"
		}
		if _, ok, e := m.knowledgeMetric(context.Background(), name, query); e != nil || !ok {
			t.Fatal(ok, e)
		}
	}
	pool := contractPool(t, queryStep("SELECT count(*) FROM knowledge_index_job", sqlValues(2, 3, 4, 5, 6, 7)))
	m.p6.Store = &knowledge.Store{Pool: pool}
	if len(m.knowledgeMetricNames()) < 7 {
		t.Fatal("missing database metric names")
	}
	value, ok, e := m.knowledgeMetric(context.Background(), "personalos.knowledge.jobs.pending", nil)
	if e != nil || !ok || value.(actuatorMetric).Measurements[0].Value != 2 {
		t.Fatal(value, ok, e)
	}
}
