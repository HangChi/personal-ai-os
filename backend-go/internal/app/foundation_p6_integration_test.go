//go:build integration

package app

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/platform/config"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func p6Upstream(t *testing.T, calls *atomic.Int32, answers ...*atomic.Int32) *httptest.Server {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input map[string]any
		if json.NewDecoder(r.Body).Decode(&input) != nil {
			http.Error(w, "body", 400)
			return
		}
		if strings.HasSuffix(r.URL.Path, "/embeddings") {
			xs, ok := input["input"].([]any)
			if !ok {
				xs = []any{input["input"]}
			}
			data := []any{}
			for i := range xs {
				vector := make([]float32, 1024)
				vector[0] = 1
				data = append(data, map[string]any{"index": i, "embedding": vector})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"data": data})
			return
		}
		calls.Add(1)
		if input["stream"] == true {
			for _, counter := range answers {
				counter.Add(1)
			}
			p4Text(w, "根据资料[1]，执行数据库轮询。")
			return
		}
		content := `{"queries":[]}`
		messages, _ := input["messages"].([]any)
		raw, _ := json.Marshal(messages)
		if bytes.Contains(raw, []byte("判断本轮是否需要知识库资料")) {
			route, _ := json.Marshal(map[string]any{"retrieve": true, "query": "证据核验怎么处理"})
			content = string(route)
		} else if bytes.Contains(raw, []byte("逐字转录")) {
			content = strings.Repeat("图片里的可读文字。", 50)
		} else if bytes.Contains(raw, []byte("evidence")) || bytes.Contains(raw, []byte("证据")) {
			content = `{"invalid":true}`
		} else if bytes.Contains(raw, []byte("rank")) {
			content = `{"order":[]}`
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": content}}}})
	}))
	t.Cleanup(s.Close)
	return s
}
func p6Foundation(t *testing.T, role string, upstream *httptest.Server, extra map[string]string, candidate *redisclient.Client, embedding ...*redisclient.Client) *Foundation {
	t.Helper()
	values := map[string]string{"app.runtime.role": role, "app.rate-limit.enabled": "false", "app.auth.enabled": "false", "app.security.csrf-enabled": "false", "app.calendar.scan-enabled": "false", "app.memory.backfill.enabled": "false", "app.context.summary.enabled": "false", "app.context.auto-memory.enabled": "false", "app.chat.upload-dir": t.TempDir(), "spring.ai.model.chat": "openai", "spring.ai.model.embedding": "openai", "spring.ai.openai.api-key": "synthetic-p6", "spring.ai.openai.base-url": upstream.URL, "spring.ai.openai.chat.model": "fixture-p6", "spring.ai.openai.embedding.model": "fixture-1024", "app.knowledge.jobs.poll-ms": "50", "app.knowledge.search.poll-ms": "50", "app.knowledge.rag.review-poll-ms": "50", "app.knowledge.rag.enabled": "false"}
	for k, v := range extra {
		values[k] = v
	}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	var embed *redisclient.Client
	if len(embedding) > 0 {
		embed = embedding[0]
	}
	f, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p6-foundation"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true, EnableP5: true, EnableP6: true, ModelTransport: upstream.Client().Transport, CandidateRedis: candidate, EmbeddingRedis: embed}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if e := f.Close(ctx); e != nil {
			t.Error(e)
		}
	})
	return f
}

func TestFoundationP6RealFacilitiesRolesAndCacheFailure(t *testing.T) {
	rabbit, search := os.Getenv("GO_TEST_KNOWLEDGE_RABBIT_URI"), os.Getenv("GO_TEST_KNOWLEDGE_SEARCH_URL")
	if rabbit == "" || search == "" {
		t.Skip("需要本次专用四设施")
	}
	var calls atomic.Int32
	up := p6Upstream(t, &calls)
	values := map[string]string{"app.rabbitmq.enabled": "true", "app.rabbitmq.uri": rabbit, "app.knowledge.search.enabled": "true", "app.knowledge.search.endpoint": search, "app.knowledge.search.index": "p6-roles", "app.knowledge.rag.ingestion-review-enabled": "true", "app.knowledge.rag.enabled": "true", "app.redis.enabled": "true", "app.redis.namespace": "fixture:embedding", "app.knowledge.candidate-cache.enabled": "true"}
	open := func(role string) *Foundation {
		return p6Foundation(t, role, up, values, fixtureAppRedis(t, "GO_TEST_REDIS_CANDIDATE_URI"), fixtureAppRedis(t, "GO_TEST_REDIS_EMBEDDING_URI"))
	}
	first := open("api")
	p6Clean(t, first)
	defer p6Clean(t, first)
	second := open("api")
	open("index")
	review := open("review")
	delivery := open("delivery")
	code, root := p6Upload(t, first.Handler(), "POST", "/api/v1/knowledge/jobs", "roles.md", strings.Repeat("RabbitMQ 唤醒与数据库轮询保持任务可靠，Redis 缓存失败回源。", 20))
	if code != 202 {
		t.Fatal(code, root)
	}
	id := root["data"].(map[string]any)["id"].(string)
	started := time.Now()
	wait := func(check func() bool) {
		t.Helper()
		deadline := time.Now().Add(20 * time.Second)
		for !check() {
			if time.Now().After(deadline) {
				t.Fatal("role convergence timeout")
			}
			time.Sleep(50 * time.Millisecond)
		}
	}
	var job knowledge.Job
	wait(func() bool {
		var e error
		job, e = second.Knowledge().Jobs.Get(context.Background(), id)
		if e != nil {
			t.Fatal(e)
		}
		return job.Status == "SUCCEEDED"
	})
	indexedMs := time.Since(started).Milliseconds()
	wait(func() bool {
		n, e := delivery.Knowledge().Projection.Pending(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		return n == 0
	})
	wait(func() bool {
		sources, e := review.Knowledge().Governance.Sources(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		return len(sources) == 1 && sources[0].Source.ReviewState == "CHECKED_CANDIDATES"
	})
	refresh, e := http.Post(search+"/p6-roles/_refresh", "application/json", nil)
	if e != nil {
		t.Fatal(e)
	}
	refresh.Body.Close()
	for range 2 {
		hits, e := second.Knowledge().Retriever.Candidates(context.Background(), "RabbitMQ", 10)
		if e != nil || len(hits) == 0 {
			t.Fatal(hits, e)
		}
	}
	counters, _ := second.Knowledge().Stats.Snapshot()
	if counters["candidate.cache|hit"] == 0 {
		t.Fatal("dedicated candidate cache unused", counters)
	}
	// Drop the borrowed cache connection; retrieval must still succeed through PostgreSQL.
	cache := second.Knowledge().Retriever.Cache.Client.(*redisclient.Client)
	if e := cache.Close(); e != nil {
		t.Fatal(e)
	}
	hits, e := second.Knowledge().Retriever.Candidates(context.Background(), "Redis", 10)
	if e != nil || len(hits) == 0 {
		t.Fatal("cache outage", hits, e)
	}
	server := httptest.NewServer(first.Handler())
	defer server.Close()
	code, root = appRequest(t, server.Client(), "GET", server.URL+"/api/v1/knowledge/search-index", "", "")
	if code != 200 || root["data"].(map[string]any)["pending"] != float64(0) {
		t.Fatal(code, root)
	}
	report, _ := json.Marshal(map[string]any{"suite": "real_facilities_roles", "index_completion_ms": indexedMs, "review_projection_completion_ms": time.Since(started).Milliseconds(), "pending_projection": 0, "redis_cache_hits": counters["candidate.cache|hit"], "cache_outage_fallback": true, "roles": []string{"api", "api", "index", "review", "delivery"}})
	t.Log("P6_EVAL " + string(report))
}

func TestFoundationP6CombinedBrokerOutageAndDisabledFacilities(t *testing.T) {
	var calls atomic.Int32
	up := p6Upstream(t, &calls)
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	address := listener.Addr().String()
	listener.Close()
	f := p6Foundation(t, "combined", up, map[string]string{"app.rabbitmq.enabled": "true", "app.rabbitmq.uri": "amqp://fixture:synthetic@" + address + "/", "app.knowledge.search.enabled": "false", "app.knowledge.search.endpoint": "${UNSET_P6_ENDPOINT}", "app.knowledge.rag.reranker.enabled": "false", "app.knowledge.rag.reranker.api-key": "${UNSET_P6_SECRET}", "app.knowledge.candidate-cache.enabled": "false"}, nil)
	p6Clean(t, f)
	defer p6Clean(t, f)
	code, root := p6Upload(t, f.Handler(), "POST", "/api/v1/knowledge/jobs", "outage.md", strings.Repeat("消息代理不可用时仍从数据库领取索引任务。", 30))
	if code != 202 {
		t.Fatal(code, root)
	}
	id := root["data"].(map[string]any)["id"].(string)
	deadline := time.Now().Add(10 * time.Second)
	for {
		job, e := f.Knowledge().Jobs.Get(context.Background(), id)
		if e != nil {
			t.Fatal(e)
		}
		if job.Status == "SUCCEEDED" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("broker outage lost persisted work", job)
		}
		time.Sleep(20 * time.Millisecond)
	}
	counts, _ := f.Knowledge().Stats.Snapshot()
	if counts["work.connections|retry"] == 0 {
		t.Fatal("broker failure not exercised", counts)
	}
}
func p6Upload(t *testing.T, h http.Handler, method, path, name, body string) (int, map[string]any) {
	t.Helper()
	var b bytes.Buffer
	m := multipart.NewWriter(&b)
	part, e := m.CreateFormFile("file", name)
	if e != nil {
		t.Fatal(e)
	}
	_, _ = io.WriteString(part, body)
	_ = m.Close()
	req := httptest.NewRequest(method, path, &b)
	req.Header.Set("Content-Type", m.FormDataContentType())
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var root map[string]any
	if e = json.Unmarshal(rec.Body.Bytes(), &root); e != nil {
		t.Fatal(rec.Body.String())
	}
	return rec.Code, root
}
func p6Clean(t *testing.T, f *Foundation) {
	t.Helper()
	appExec(t, f, `DELETE FROM knowledge_index_job; DELETE FROM knowledge_document; DELETE FROM vector_store; DELETE FROM knowledge_search_outbox; DELETE FROM work_signal_outbox; DELETE FROM knowledge_conflict_audit; DELETE FROM knowledge_space WHERE id<>'00000000-0000-0000-0000-000000000001'`)
}

func TestFoundationP6DocumentsAPIAndRolePolling(t *testing.T) {
	var calls atomic.Int32
	up := p6Upstream(t, &calls)
	api := p6Foundation(t, "api", up, nil, nil)
	p6Clean(t, api)
	defer p6Clean(t, api)
	content := "# P6 运维\n\n" + strings.Repeat("数据库轮询是持久索引任务的可靠兜底。", 30)
	code, root := p6Upload(t, api.Handler(), "POST", "/api/v1/knowledge/documents", "p6.md", content)
	if code != 201 {
		t.Fatal(code, root)
	}
	id := root["data"].(map[string]any)["id"].(string)
	server := httptest.NewServer(api.Handler())
	defer server.Close()
	client := server.Client()
	code, root = appRequest(t, client, "GET", server.URL+"/api/v1/knowledge/documents/search?keyword=p6", "", "")
	if code != 200 || root["data"].(map[string]any)["total"] != float64(1) {
		t.Fatal(code, root)
	}
	code, root = appRequest(t, client, "POST", server.URL+"/api/v1/knowledge/search", "", `{"query":"数据库轮询","topK":1}`)
	if code != 200 || len(root["data"].(map[string]any)["hits"].([]any)) != 1 {
		t.Fatal(code, root)
	}
	for _, test := range []struct {
		path, body string
		status     int
	}{{"search", `{"query":" "}`, 400}, {"search", `{"query":"` + strings.Repeat("字", 501) + `"}`, 400}, {"search", `{"Query":"x"}`, 400}, {"ask", `{"question":[]}`, 400}, {"search-index", "", 404}} {
		method := "POST"
		if test.path == "search-index" {
			method = "GET"
		}
		status, v := appRequest(t, client, method, server.URL+"/api/v1/knowledge/"+test.path, "", test.body)
		if status != test.status {
			t.Fatal(test, status, v)
		}
	}
	code, root = p6Upload(t, api.Handler(), "PUT", "/api/v1/knowledge/documents/"+id, "changed.txt", content+"更新版本")
	if code != 200 || root["data"].(map[string]any)["id"] != id {
		t.Fatal(code, root)
	}
	code, root = p6Upload(t, api.Handler(), "POST", "/api/v1/knowledge/jobs", "queued.md", content)
	if code != 202 {
		t.Fatal(code, root)
	}
	jobID := root["data"].(map[string]any)["id"].(string)
	job, e := api.Knowledge().Jobs.Get(context.Background(), jobID)
	if e != nil || job.Status != "PENDING" {
		t.Fatal("API consumed index work", job, e)
	}
	index := p6Foundation(t, "index", up, nil, nil)
	deadline := time.Now().Add(10 * time.Second)
	for {
		job, e = api.Knowledge().Jobs.Get(context.Background(), jobID)
		if e != nil {
			t.Fatal(e)
		}
		if job.Status == "SUCCEEDED" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("index polling", job)
		}
		time.Sleep(20 * time.Millisecond)
	}
	for _, worker := range []*Foundation{index, p6Foundation(t, "review", up, nil, nil), p6Foundation(t, "delivery", up, nil, nil)} {
		rec := httptest.NewRecorder()
		worker.Handler().ServeHTTP(rec, httptest.NewRequest("GET", "/api/v1/knowledge/documents", nil))
		if rec.Code != 404 {
			t.Fatal("worker exposed API", rec.Code)
		}
	}
	second := p6Foundation(t, "api", up, nil, nil)
	docs, e := second.Knowledge().Store.List(context.Background())
	if e != nil || len(docs) != 2 {
		t.Fatal("dual API", docs, e)
	}
	code, root = appRequest(t, client, "DELETE", server.URL+"/api/v1/knowledge/documents/"+id, "", "")
	if code != 200 || root["data"].(map[string]any)["deleted"] != true {
		t.Fatal(code, root)
	}
	if calls.Load() != 0 {
		t.Fatal("retrieval/ingestion called generation", calls.Load())
	}
}

func TestFoundationP6DisabledIndexStillAcceptsDurableJobs(t *testing.T) {
	var calls atomic.Int32
	up := p6Upstream(t, &calls)
	f := p6Foundation(t, "combined", up, map[string]string{"app.knowledge.jobs.enabled": "false"}, nil)
	p6Clean(t, f)
	defer p6Clean(t, f)
	code, root := p6Upload(t, f.Handler(), "POST", "/api/v1/knowledge/jobs", "disabled.md", strings.Repeat("关闭后台调度仍保留持久任务。", 40))
	if code != 202 {
		t.Fatal(code, root)
	}
	id := root["data"].(map[string]any)["id"].(string)
	time.Sleep(200 * time.Millisecond)
	job, e := f.Knowledge().Jobs.Get(context.Background(), id)
	if e != nil || job.Status != "PENDING" || job.Attempts != 0 {
		t.Fatal("disabled scheduler claimed a job", job, e)
	}
	index := p6Foundation(t, "index", up, nil, nil)
	deadline := time.Now().Add(10 * time.Second)
	for {
		job, e = index.Knowledge().Jobs.Get(context.Background(), id)
		if e != nil {
			t.Fatal(e)
		}
		if job.Status == "SUCCEEDED" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("enabled index did not recover durable work", job)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestFoundationP6SecurityGovernanceAndGuardedChat(t *testing.T) {
	var calls, answers atomic.Int32
	up := p6Upstream(t, &calls, &answers)
	f := p6Foundation(t, "api", up, map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true", "app.knowledge.rag.enabled": "true", "app.knowledge.rag.ingestion-review-enabled": "false"}, nil)
	p6Clean(t, f)
	defer p6Clean(t, f)
	s := httptest.NewServer(f.Handler())
	defer s.Close()
	c := s.Client()
	code, _ := appRequest(t, c, "GET", s.URL+"/api/v1/knowledge/documents", "", "")
	if code != 401 {
		t.Fatal(code)
	}
	code, _ = appRequest(t, c, "POST", s.URL+"/api/v1/knowledge/search", "", `{"query":"x"}`)
	if code != 403 {
		t.Fatal(code)
	}
	c.Jar, _ = cookiejar.New(nil)
	_, root := appRequest(t, c, "GET", s.URL+"/api/v1/auth/csrf", "", "")
	token := root["data"].(map[string]any)["token"].(string)
	code, _ = appRequest(t, c, "POST", s.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`)
	if code != 200 {
		t.Fatal(code)
	}
	_, root = appRequest(t, c, "GET", s.URL+"/api/v1/auth/csrf", "", "")
	token = root["data"].(map[string]any)["token"].(string)
	doc, e := f.Knowledge().Store.Ingest(context.Background(), "guarded.md", "", []byte(strings.Repeat("证据未核验时只展示资料，不生成确定结论。", 30)))
	if e != nil {
		t.Fatal(e)
	}
	code, root = appRequest(t, c, "POST", s.URL+"/api/v1/knowledge/governance/spaces", token, `{"name":"P6治理"}`)
	if code != 200 {
		t.Fatal(code, root)
	}
	space := root["data"].(map[string]any)["id"].(string)
	code, root = appRequest(t, c, "PUT", s.URL+"/api/v1/knowledge/governance/sources/"+doc.ID, token, fmt.Sprintf(`{"spaceId":%q,"version":"2026","lifecycle":"ACTIVE"}`, space))
	if code != 200 {
		t.Fatal(code, root)
	}
	code, root = appRequest(t, c, "POST", s.URL+"/api/v1/knowledge/ask", token, `{"question":"证据核验怎么处理"}`)
	if code != 200 || root["data"].(map[string]any)["answered"] != false {
		t.Fatal(code, root)
	}
	code, root = appRequest(t, c, "POST", s.URL+"/api/v1/chat/sessions", token, `{"title":"P6 guarded"}`)
	if code != 201 {
		t.Fatal(code, root)
	}
	id := root["data"].(map[string]any)["conversationId"].(string)
	before := calls.Load()
	req, _ := http.NewRequest("POST", s.URL+"/api/v1/chat/sessions/"+id+"/stream", strings.NewReader(`{"content":"证据核验怎么处理","useKnowledge":true}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-CSRF-TOKEN", token)
	resp, e := c.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	text := string(b)
	if resp.StatusCode != 200 || !strings.Contains(text, "event: rag") || !strings.Contains(text, "暂不生成确定结论") || strings.Contains(text, "event: error") {
		t.Fatal(resp.StatusCode, text)
	}
	if calls.Load()-before != 4 || answers.Load() != 0 {
		t.Fatal("guarded chat generated an answer", calls.Load()-before)
	}
	messages, e := f.Chat().Service.Messages(context.Background(), id, nil, nil)
	if e != nil || len(messages) != 2 || len(messages[1].Citations) != 1 {
		t.Fatal(messages, e)
	}
}
