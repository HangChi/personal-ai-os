//go:build integration

package app

import (
	"context"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func TestP8KnowledgeRealDatabaseGaugesAndRecall(t *testing.T) {
	var calls atomic.Int32
	upstream := p6Upstream(t, &calls)
	foundation := p6Foundation(t, "api", upstream, nil, nil)
	p6Clean(t, foundation)
	defer p6Clean(t, foundation)
	ids := []string{}
	for _, filename := range []string{"ready.txt", "failed.txt", "future.txt"} {
		code, response := p6Upload(t, foundation.Handler(), "POST", "/api/v1/knowledge/documents", filename, strings.Repeat("P8 synthetic document with enough context for embedding. ", 20))
		if code != 201 {
			t.Fatal(code, response)
		}
		ids = append(ids, response["data"].(map[string]any)["id"].(string))
	}
	appExec(t, foundation, "UPDATE knowledge_review_job SET next_attempt_at=now()-interval '90 seconds' WHERE document_id='"+ids[0]+"'; UPDATE knowledge_review_job SET next_attempt_at='infinity' WHERE document_id='"+ids[1]+"'; UPDATE knowledge_review_job SET next_attempt_at=now()+interval '1 hour' WHERE document_id='"+ids[2]+"'; DELETE FROM knowledge_search_outbox; INSERT INTO knowledge_search_outbox(chunk_id) VALUES(uuid_generate_v4());")
	for index, status := range []string{"PENDING", "PENDING", "RUNNING", "SUCCEEDED", "FAILED"} {
		available := "now()-interval '120 seconds'"
		if index == 0 {
			available = "now()+interval '1 hour'"
		}
		appExec(t, foundation, "INSERT INTO knowledge_index_job(id,document_id,replacing,filename,digest,status,available_at) VALUES(uuid_generate_v4(),uuid_generate_v4(),false,'p8.txt','synthetic','"+status+"',"+available+");")
	}
	module := foundation.Knowledge()
	gauges, err := module.Store.MetricGauges(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for name, expected := range map[string]float64{"jobs.pending": 3, "review.ready": 1, "review.failed": 1, "search.pending": 1} {
		if gauges[name] != expected {
			t.Errorf("%s = %g, want %g", name, gauges[name], expected)
		}
	}
	if gauges["jobs.oldest.ready.seconds"] < 120 || gauges["jobs.oldest.ready.seconds"] > 140 || gauges["review.oldest.ready.seconds"] < 90 {
		t.Fatal(gauges)
	}
	if _, err := module.Retriever.Candidates(context.Background(), "synthetic document", 3); err != nil {
		t.Fatal(err)
	}
	_, timers := module.Stats.Snapshot()
	if timers["recall.postgres_vector_path|"].Count != 1 {
		t.Fatal(timers)
	}
	metrics := &foundationMetrics{p6: module, knowledgeRecallHistogram: true}
	response := httptest.NewRecorder()
	metrics.ServeHTTP(response, httptest.NewRequest("GET", "/actuator/prometheus", nil))
	for _, expected := range []string{"personalos_knowledge_jobs_pending 3", "personalos_knowledge_review_failed 1", "personalos_knowledge_search_pending 1", "personalos_knowledge_recall_seconds_count{stage=" + strconv.Quote("postgres_vector_path") + "} 1"} {
		if !strings.Contains(response.Body.String(), expected) {
			t.Error("missing real scrape value", expected)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	response = httptest.NewRecorder()
	metrics.writeKnowledgeMetrics(response, ctx)
	if !strings.Contains(response.Body.String(), "personalos_knowledge_jobs_pending NaN") {
		t.Error("database failure incorrectly reported as zero", response.Body.String())
	}
}
