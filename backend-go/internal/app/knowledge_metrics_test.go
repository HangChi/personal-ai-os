package app

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/knowledge"
)

func knowledgeOracleKey(raw string) string {
	name, tags, hasTags := strings.Cut(raw, "{")
	if !hasTags {
		return name
	}
	values := strings.Split(strings.TrimSuffix(tags, "}"), ",")
	for i, value := range values {
		if strings.HasPrefix(value, "le=") {
			label, err := strconv.Unquote(strings.TrimPrefix(value, "le="))
			if err != nil {
				panic(err)
			}
			number, err := strconv.ParseFloat(label, 64)
			if err != nil {
				panic(err)
			}
			values[i] = "le=" + strconv.Quote(strconv.FormatFloat(number, 'g', -1, 64))
		}
	}
	slices.Sort(values)
	return name + "{" + strings.Join(values, ",") + "}"
}

func TestP8KnowledgeMetricsAgainstActualJavaRegistry(t *testing.T) {
	raw, err := os.ReadFile("testdata/java-knowledge-metrics.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct{ Samples map[string]float64 }
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	stats := knowledge.NewStats()
	stats.Count("jobs.attempts", "success")
	stats.Count("rag.results", "READY")
	stats.Add("rag.tokens", "input", 17)
	stats.Add("rag.rerank.tokens", "total", 9)
	stats.Duration("jobs.duration", "", 2*time.Second)
	stats.Duration("rag.stage.recall", "OK", 250*time.Millisecond)
	stats.Duration("recall.query_embedding", "", 10*time.Millisecond)
	stats.Active.Store(2)
	metrics := &foundationMetrics{p6: &knowledge.Module{Stats: stats}, knowledgeRecallHistogram: true, knowledgeRagHistogram: true}
	response := httptest.NewRecorder()
	metrics.writeKnowledgeMetrics(response, context.Background())
	actual := map[string]float64{}
	for _, line := range strings.Split(response.Body.String(), string(rune(10))) {
		if !strings.HasPrefix(line, "personalos_knowledge_") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) != 2 {
			t.Fatal("invalid Prometheus sample", line)
		}
		number, err := strconv.ParseFloat(parts[1], 64)
		if err != nil {
			t.Fatal(err)
		}
		actual[knowledgeOracleKey(parts[0])] = number
	}
	if len(actual) != len(fixture.Samples) {
		t.Fatalf("sample count Go=%d Java=%d", len(actual), len(fixture.Samples))
	}
	for key, expected := range fixture.Samples {
		value, exists := actual[knowledgeOracleKey(key)]
		if !exists || value != expected {
			t.Errorf("%s = %g (%t), original Java = %g", key, value, exists, expected)
		}
	}
}

func TestP8KnowledgeMetricNamesLabelsAndUnits(t *testing.T) {
	stats := knowledge.NewStats()
	stats.Count("jobs.attempts", "success")
	stats.Count("rag.results", "READY")
	stats.Add("rag.tokens", "input", 17)
	stats.Add("rag.rerank.tokens", "total", 9)
	stats.Duration("jobs.duration", "", 2*time.Second)
	stats.Duration("rag.stage.recall", "OK", 250*time.Millisecond)
	stats.Duration("rag.stage.recall", "FALLBACK", time.Second)
	stats.Duration("recall.query_embedding", "", 10*time.Millisecond)
	stats.Active.Store(2)
	metrics := &foundationMetrics{p6: &knowledge.Module{Stats: stats}, knowledgeRecallHistogram: true, knowledgeRagHistogram: true}
	response := httptest.NewRecorder()
	metrics.ServeHTTP(response, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	body := response.Body.String()
	q := strconv.Quote
	for _, expected := range []string{
		"personalos_knowledge_jobs_active 2",
		"personalos_knowledge_jobs_attempts_total{outcome=" + q("success") + "} 1",
		"personalos_knowledge_rag_results_total{status=" + q("READY") + "} 1",
		"personalos_knowledge_rag_tokens_total{kind=" + q("input") + "} 17",
		"personalos_knowledge_rag_rerank_tokens_total 9",
		"personalos_knowledge_jobs_duration_seconds_sum 2",
		"personalos_knowledge_jobs_duration_seconds_count 1",
		"# TYPE personalos_knowledge_rag_stage_seconds histogram",
		"personalos_knowledge_rag_stage_seconds_sum{result=" + q("OK") + ",stage=" + q("recall") + "} 0.25",
		"personalos_knowledge_recall_seconds_bucket{le=" + q("+Inf") + ",stage=" + q("query_embedding") + "} 1",
	} {
		if !strings.Contains(body, expected) {
			t.Errorf("missing metric: %s", expected)
		}
	}
	for _, removed := range []string{"nanoseconds_total", "personalos_knowledge_active", "jobs_attempts_total{result="} {
		if strings.Contains(body, removed) {
			t.Error("obsolete metric retained", removed)
		}
	}
	if strings.Count(body, "# TYPE personalos_knowledge_rag_stage_seconds histogram") != 1 {
		t.Error("duplicate family TYPE")
	}
	value, found, err := metrics.metricSnapshot("/personalos.knowledge.rag.stage", url.Values{"tag": {"stage:recall", "result:OK"}})
	if err != nil || !found {
		t.Fatal(value, found, err)
	}
	metric := value.(actuatorMetric)
	if metric.BaseUnit != "seconds" || len(metric.AvailableTags) != 0 || metric.Measurements[0].Value != 1 || metric.Measurements[1].Value != .25 || metric.Measurements[2].Value != .25 {
		t.Fatal(metric)
	}
	_, found, err = metrics.metricSnapshot("/personalos.knowledge.jobs.attempts", url.Values{"tag": {"result:success"}})
	if err != nil || found {
		t.Fatal("incorrect legacy tag accepted", found, err)
	}
	if _, _, err = metrics.knowledgeMetric(context.Background(), "personalos.knowledge.rag.stage", url.Values{"tag": {"bad"}}); err == nil {
		t.Error("malformed tag accepted")
	}
	metrics.knowledgeRecallHistogram, metrics.knowledgeRagHistogram = false, false
	response = httptest.NewRecorder()
	metrics.ServeHTTP(response, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	if strings.Contains(response.Body.String(), "_bucket{") {
		t.Error("disabled histograms still exposed")
	}
}
