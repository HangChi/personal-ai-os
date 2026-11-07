package knowledge

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

func moduleSchemaSteps() []sqlStep {
	steps := []sqlStep{queryStep("SELECT format_type", sqlValues("vector(2)")), queryStep("SELECT count(*)", sqlValues(0))}
	for range 11 {
		steps = append(steps, queryStep("has_table_privilege", sqlValues(true)))
	}
	for range 3 {
		steps = append(steps, queryStep("has_sequence_privilege", sqlValues(true)))
	}
	return steps
}
func TestKnowledgeModuleExplicitFacilitiesAndFailureCleanup(t *testing.T) {
	for _, mode := range []string{"base", "cache", "rerank", "search", "review-worker", "invalid-flag", "invalid-budget", "invalid-reranker", "cache-mismatch", "empty-namespace", "invalid-poll"} {
		t.Run(mode, func(t *testing.T) {
			overrides := map[string]string{"app.knowledge.embedding-dimensions": "2", "app.rabbitmq.enabled": "false", "app.knowledge.candidate-cache.enabled": "false", "app.redis.enabled": "false"}
			role := config.API
			steps := moduleSchemaSteps()
			var redis RedisCacheClient
			switch mode {
			case "cache", "empty-namespace":
				overrides["app.knowledge.candidate-cache.enabled"] = "true"
				overrides["app.redis.enabled"] = "true"
				overrides["app.redis.namespace"] = "contract-candidate"
				if mode == "empty-namespace" {
					overrides["app.redis.namespace"] = " "
				}
				redis = contractRedis(func(context.Context, string, []string, ...any) (any, error) {
					t.Fatal("module opening must not call Redis")
					return nil, nil
				})
			case "rerank", "invalid-reranker":
				overrides["app.knowledge.rag.reranker.enabled"] = "true"
				overrides["app.knowledge.rag.reranker.endpoint"] = "https://fixture.invalid/rerank"
				overrides["app.knowledge.rag.reranker.model"] = "fixture"
				overrides["app.knowledge.rag.reranker.protocol"] = "cohere"
				if mode == "invalid-reranker" {
					overrides["app.knowledge.rag.reranker.protocol"] = "invalid"
				}
			case "search":
				overrides["app.knowledge.search.enabled"] = "true"
				overrides["app.knowledge.search.endpoint"] = "https://fixture.invalid"
				overrides["app.knowledge.search.index"] = "contract-index"
				steps = append(steps, execStep("INSERT INTO knowledge_search_outbox"))
			case "review-worker":
				role = config.Review
			case "invalid-flag":
				overrides["app.knowledge.rag.enabled"] = "perhaps"
			case "invalid-budget":
				overrides["app.knowledge.rag.budget-ms"] = "0"
			case "cache-mismatch":
				overrides["app.knowledge.candidate-cache.enabled"] = "true"
				overrides["app.redis.enabled"] = "true"
			case "invalid-poll":
				role = config.Index
				overrides["app.knowledge.jobs.poll-ms"] = "-1"
			}
			props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			s := contractStore(t, steps...)
			locks, e := coordination.New(s.Pool)
			if e != nil {
				t.Fatal(e)
			}
			defer locks.Close(context.Background())
			opts := ModuleOptions{Pool: s.Pool, Properties: props, Models: &model.Module{Runtime: model.NewRuntime(nil)}, Coordinator: locks, Role: role, CandidateRedis: redis, Acquire: func(context.Context) (func(), error) { return nil, nil }, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
			m, e := OpenModule(context.Background(), opts)
			valid := mode == "base" || mode == "cache" || mode == "rerank" || mode == "search" || mode == "review-worker"
			if (e == nil) != valid {
				t.Fatal(mode, e)
			}
			if !valid {
				if m != nil {
					t.Fatal("partial module returned")
				}
				return
			}
			defer m.Close()
			if m.Handler == nil || m.QA == nil || m.Review == nil || m.Pipeline.Recall != m.Retriever {
				t.Fatal("incomplete module")
			}
			if mode == "cache" && m.Retriever.Cache == nil || mode == "rerank" && m.Pipeline.Reranker == nil || mode == "search" && m.Projection == nil {
				t.Fatal("enabled facility absent")
			}
			m.Close()
			m.Close()
			if mode == "cache" && !m.Retriever.Cache.closed.Load() {
				t.Fatal("cache survived module close")
			}
		})
	}
	if m, e := OpenModule(context.Background(), ModuleOptions{}); e == nil || m != nil {
		t.Fatal(m, e)
	}
	var empty *Module
	empty.Close()
}
func TestKnowledgeHTTPValidationNegotiationAndSearchDelegation(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"GET", "/search", "", 405}, {"POST", "/search", "{}", 400}, {"POST", "/search", `{"query":{}}`, 400}, {"POST", "/search", `{"query":"q","topK":{}}`, 400}, {"POST", "/ask", `{"question":" "}`, 400}, {"POST", "/ask", `{"question":"` + strings.Repeat("字", 501) + `"}`, 400},
		{"GET", "/documents/search?page=invalid", "", 400}, {"GET", "/documents/search?size=2147483648", "", 400}, {"PATCH", "/documents", "", 405}, {"PATCH", "/jobs", "", 405},
		{"GET", "/jobs/bad", "", 400}, {"GET", "/jobs/" + contractDocA + "/extra", "", 404}, {"GET", "/documents/" + contractDocA + "/extra", "", 404}, {"GET", "/governance/sources/" + contractDocA, "", 405},
		{"POST", "/governance/spaces", `{"name":{}}`, 400}, {"POST", "/governance/conflicts/" + contractDocA + "/resolve", `{"decision":"KEEP_BOTH","winnerId":"bad"}`, 400},
		{"GET", "/search-index", "", 404}, {"GET", "/captures/invalid/versions", "", 400}, {"DELETE", "/captures", "", 405}, {"POST", "/captures", "{}", 400}, {"GET", "/unknown", "", 404},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			s := contractStore(t)
			h := &HTTP{Store: s, Governance: &Governance{s}}
			r := httptest.NewRequest(tc.method, basePath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	calls := 0
	h := &HTTP{Store: contractStore(t), Search: searchFunc(func(_ context.Context, q string, k *int) (RagResult, error) {
		calls++
		if q != "检索" || k == nil || *k != 7 {
			t.Fatal(q, k)
		}
		return RagResult{Hits: []Hit{{DocumentID: contractDocA, Content: "证据"}}}, nil
	})}
	handler := h.Handler(slog.Default())
	r := httptest.NewRequest("POST", basePath+"/search", strings.NewReader(`{"query":"检索","topK":7}`))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 200 || calls != 1 || !strings.Contains(w.Body.String(), contractDocA) {
		t.Fatal(w.Code, w.Body.String(), calls)
	}
	r = httptest.NewRequest("GET", basePath+"/documents", nil)
	r.Header.Set("Accept", "image/png")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 406 || calls != 1 {
		t.Fatal(w.Code)
	}
}
func TestKnowledgeHTTPReadEndpointsUseRealStores(t *testing.T) {
	for _, tc := range []struct {
		path, match string
		rows        [][]any
	}{
		{"/documents", "ORDER BY created_at DESC", nil}, {"/jobs", "LIMIT 20", nil}, {"/jobs/" + contractDocA, "FROM knowledge_index_job WHERE id", nil}, {"/governance/spaces", "ORDER BY name", nil}, {"/governance/sources", "ORDER BY d.updated_at", nil}, {"/governance/conflicts", "ORDER BY created_at DESC LIMIT 200", nil},
	} {
		t.Run(tc.path, func(t *testing.T) {
			step := queryStep(tc.match)
			if strings.Contains(tc.path, "/jobs/") {
				step.rows = append(step.rows, jobRow(contractDocA, contractDocA, "PENDING", 0))
			}
			s := contractStore(t, step)
			h := &HTTP{Store: s, Jobs: &Jobs{Store: s}, Governance: &Governance{s}}
			w := httptest.NewRecorder()
			h.Handler(slog.Default()).ServeHTTP(w, httptest.NewRequest("GET", basePath+tc.path, nil))
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
			var body map[string]any
			if e := json.Unmarshal(w.Body.Bytes(), &body); e != nil {
				t.Fatal(e)
			}
		})
	}
	s := contractStore(t, queryStep("ORDER BY created_at DESC"))
	w := httptest.NewRecorder()
	(&HTTP{Store: s}).Handler(slog.Default()).ServeHTTP(w, httptest.NewRequest(http.MethodHead, basePath+"/documents", nil))
	if w.Code != 200 || w.Body.Len() != 0 {
		t.Fatal(w.Code, w.Body.String())
	}
}
