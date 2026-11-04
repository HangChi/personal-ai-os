package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

type ModuleOptions struct {
	Pool           *database.Pool
	Properties     *config.Properties
	Models         *model.Module
	Coordinator    *coordination.Coordinator
	Role           config.Role
	Acquire        AcquireWork
	CandidateRedis RedisCacheClient
	Transport      http.RoundTripper
	Logger         *slog.Logger
}
type Module struct {
	Store      *Store
	Jobs       *Jobs
	Governance *Governance
	Review     *ReviewJobs
	Retriever  *Retriever
	Pipeline   *Pipeline
	QA         *QA
	Projection *Projection
	Handler    http.Handler
	Stats      *Stats
	Gateway    *Gateway
	Signals    *Signals
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	closeOnce  sync.Once
}

// OpenModule consumes only enabled facilities; Redis clients remain caller-owned.
func OpenModule(parent context.Context, o ModuleOptions) (m *Module, err error) {
	if parent == nil || o.Pool == nil || o.Properties == nil || o.Models == nil || o.Coordinator == nil || o.Logger == nil || o.Acquire == nil {
		return nil, errors.New("P6模块依赖不完整")
	}
	if _, err = config.ParseRole(string(o.Role)); err != nil {
		return nil, err
	}
	get := func(key string) (string, error) { v, _, e := o.Properties.Get(key); return v, e }
	flag := func(key string, fallback bool) (bool, error) {
		v, found, e := o.Properties.Get(key)
		if e != nil {
			return false, e
		}
		if !found {
			return fallback, nil
		}
		return strconv.ParseBool(v)
	}
	duration := func(key string, fallback time.Duration) (time.Duration, error) {
		v, found, e := o.Properties.Get(key)
		if e != nil {
			return 0, e
		}
		if !found {
			return fallback, nil
		}
		n, e := strconv.ParseInt(v, 10, 32)
		if e != nil || n <= 0 {
			return 0, fmt.Errorf("%s 必须为正整数毫秒", key)
		}
		return time.Duration(n) * time.Millisecond, nil
	}
	values := map[string]string{}
	for _, suffix := range []string{"embedding-dimensions", "top-k", "similarity-threshold", "max-file-size-bytes", "allowed-extensions", "min-chunk-size-chars", "chunk-size-chars", "chunk-overlap-chars", "hybrid-search-enabled", "rrf-k", "keyword-channel-weight"} {
		key := "app.knowledge." + suffix
		v, found, e := o.Properties.Get(key)
		if e != nil {
			return nil, e
		}
		if found {
			values[key] = v
		}
	}
	settings, e := config.BindKnowledgeProperties(values)
	if e != nil {
		return nil, e
	}
	schema, e := get("spring.ai.vectorstore.pgvector.schema-name")
	if e != nil {
		return nil, e
	}
	table, e := get("spring.ai.vectorstore.pgvector.table-name")
	if e != nil {
		return nil, e
	}
	if schema == "" {
		schema = "public"
	}
	if table == "" {
		table = "vector_store"
	}
	store, e := NewStore(parent, o.Pool, Reader{Properties: settings, Vision: Vision(o.Models.Runtime)}, o.Models.Embedding, o.Coordinator, schema, table)
	if e != nil {
		return nil, e
	}
	if e = checkPrivileges(parent, store); e != nil {
		return nil, e
	}
	ctx, cancel := context.WithCancel(parent)
	m = &Module{Store: store, Stats: NewStats(), cancel: cancel}
	defer func() {
		if err != nil {
			m.Close()
		}
	}()
	m.Jobs = &Jobs{Store: store, Acquire: o.Acquire, Stats: m.Stats}
	m.Governance = &Governance{Store: store}
	m.Retriever = &Retriever{Store: store, Stats: m.Stats}
	m.Gateway = NewGateway(ctx, o.Models.Runtime, m.Stats)
	enabled, e := flag("app.knowledge.rag.enabled", true)
	if e != nil {
		return nil, e
	}
	budget, e := duration("app.knowledge.rag.budget-ms", 20*time.Second)
	if e != nil {
		return nil, e
	}
	evidence, e := duration("app.knowledge.rag.evidence-timeout-ms", 12*time.Second)
	if e != nil {
		return nil, e
	}
	reviewer := &EvidenceReviewer{Model: m.Gateway}
	m.Pipeline = &Pipeline{Recall: m.Retriever, Model: m.Gateway, Review: reviewer, Governance: m.Governance, Enabled: enabled, Budget: budget, EvidenceTimeout: evidence, TopK: settings.TopK, RRFK: settings.RRFK, Stats: m.Stats}
	rerank, e := flag("app.knowledge.rag.reranker.enabled", false)
	if e != nil {
		return nil, e
	}
	if enabled && rerank {
		v := []string{}
		for _, key := range []string{"endpoint", "api-key", "model", "protocol"} {
			s, e := get("app.knowledge.rag.reranker." + key)
			if e != nil {
				return nil, e
			}
			v = append(v, s)
		}
		rank, e := NewCrossEncoder(v[0], v[1], v[2], v[3], o.Transport)
		if e != nil {
			return nil, e
		}
		rank.Stats = m.Stats
		m.Pipeline.Reranker = rank
	}
	var searchGateway *OpenSearch
	search, e := flag("app.knowledge.search.enabled", false)
	if e != nil {
		return nil, e
	}
	if search {
		v := []string{}
		for _, key := range []string{"endpoint", "index", "username", "password"} {
			s, e := get("app.knowledge.search." + key)
			if e != nil {
				return nil, e
			}
			v = append(v, s)
		}
		gateway, e := NewOpenSearch(v[0], v[1], v[2], v[3], settings.EmbeddingDimensions, o.Transport)
		if e != nil {
			return nil, e
		}
		m.Projection = &Projection{Store: store, Gateway: gateway, Stats: m.Stats}
		m.Retriever.External = gateway
		searchGateway = gateway
		// Projection retry initializes the mapping as well; outages cannot prevent DB recall.
		if o.Role.Allows("api") {
			if _, e = m.Projection.Reindex(parent); e != nil {
				return nil, e
			}
		}
	}
	cache, e := flag("app.knowledge.candidate-cache.enabled", false)
	if e != nil {
		return nil, e
	}
	redis, e := flag("app.redis.enabled", false)
	if e != nil {
		return nil, e
	}
	if (cache && redis) != (o.CandidateRedis != nil) {
		return nil, errors.New("候选缓存开关必须与显式专用Redis客户端一致")
	}
	if cache && redis {
		ns, e := get("app.redis.namespace")
		if e != nil {
			return nil, e
		}
		if strip(ns) == "" {
			return nil, errors.New("候选缓存必须显式配置namespace")
		}
		var fingerprint strings.Builder
		for _, key := range []string{"spring.ai.model.embedding", "spring.ai.openai.base-url", "spring.ai.openai.embedding.model", "spring.ai.ollama.base-url", "spring.ai.ollama.embedding.model", "app.embedding-cache.revision", "app.knowledge.search.endpoint", "app.knowledge.search.index"} {
			v, e := get(key)
			if e != nil {
				return nil, e
			}
			fingerprint.WriteString(key + "=" + v + "\n")
		}
		m.Retriever.Cache, e = NewCandidateCache(store, o.CandidateRedis, ns, fingerprint.String())
		if e != nil {
			return nil, e
		}
		m.Retriever.Cache.Stats = m.Stats
	}
	m.QA = &QA{Search: m.Pipeline, Answer: func(ctx context.Context, r model.ChatRequest) (model.ChatResult, error) {
		client, e := o.Models.Runtime.Target(ctx, "")
		if e != nil {
			return model.ChatResult{}, e
		}
		return client.Call(ctx, r)
	}}
	m.Review = &ReviewJobs{Store: store, Governance: m.Governance, Reviewer: reviewer, Recall: m.Retriever, Acquire: o.Acquire, Timeout: evidence, Stats: m.Stats}
	m.Handler = (&HTTP{Store: store, Jobs: m.Jobs, Governance: m.Governance, Search: m.Pipeline, QA: m.QA, Projection: m.Projection}).Handler(o.Logger)
	wakeups := map[string]func(){}
	start := func(queue, key string, fallback, initial time.Duration, run func(context.Context) error) error {
		interval, e := duration(key, fallback)
		if e != nil {
			return e
		}
		wake := make(chan struct{}, 1)
		if queue != "" {
			wakeups[queue] = func() {
				select {
				case wake <- struct{}{}:
				default:
				}
			}
		}
		m.wg.Go(func() {
			timer := time.NewTimer(initial)
			defer timer.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-wake:
				case <-timer.C:
				}
				if ctx.Err() != nil {
					return
				}
				if e := run(ctx); e != nil && ctx.Err() == nil {
					o.Logger.WarnContext(ctx, "知识后台任务暂不可用，将轮询重试")
				}
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				timer.Reset(interval)
			}
		})
		return nil
	}
	indexQueue := "personalos.knowledge.ready"
	rabbit, e := flag("app.rabbitmq.enabled", false)
	if e != nil {
		return nil, e
	}
	if rabbit {
		indexQueue, e = get("app.rabbitmq.queue")
		if e != nil {
			return nil, e
		}
	}
	jobsEnabled, e := flag("app.knowledge.jobs.enabled", true)
	if e != nil {
		return nil, e
	}
	if o.Role.Allows("index") && jobsEnabled {
		if e = start(indexQueue, "app.knowledge.jobs.poll-ms", time.Second, 0, func(ctx context.Context) error { _, e := m.Jobs.RunNext(ctx); return e }); e != nil {
			return nil, e
		}
		if e = start("", "app.knowledge.jobs.cleanup-ms", time.Hour, time.Minute, m.Jobs.Cleanup); e != nil {
			return nil, e
		}
	}
	review, e := flag("app.knowledge.rag.ingestion-review-enabled", true)
	if e != nil {
		return nil, e
	}
	if o.Role.Allows("review") && enabled && review {
		if e = start(ReviewQueue, "app.knowledge.rag.review-poll-ms", 5*time.Second, 10*time.Second, func(ctx context.Context) error { _, e := m.Review.RunNext(ctx); return e }); e != nil {
			return nil, e
		}
	}
	if o.Role.Allows("delivery") && m.Projection != nil {
		if e = start(SearchQueue, "app.knowledge.search.poll-ms", 2*time.Second, 0, func(ctx context.Context) error {
			if e := searchGateway.Initialize(ctx); e != nil {
				return e
			}
			_, e := m.Projection.RunNext(ctx)
			return e
		}); e != nil {
			return nil, e
		}
	}
	if rabbit {
		uri, e := get("app.rabbitmq.uri")
		if e != nil {
			return nil, e
		}
		m.Signals, e = StartSignals(ctx, uri, indexQueue, store, o.Role.Allows("api"), wakeups, m.Stats, o.Logger)
		if e != nil {
			return nil, e
		}
	}
	return m, nil
}

func (m *Module) Close() {
	if m == nil {
		return
	}
	m.closeOnce.Do(func() {
		m.cancel()
		if m.Signals != nil {
			m.Signals.Close()
		}
		if m.Gateway != nil {
			m.Gateway.Close()
		}
		m.wg.Wait()
		if m.Retriever != nil && m.Retriever.Cache != nil {
			m.Retriever.Cache.Close()
		}
	})
}

func checkPrivileges(ctx context.Context, s *Store) error {
	return s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		for _, table := range []string{"knowledge_document", s.table, "knowledge_index_job", "knowledge_space", "knowledge_source", "knowledge_review_job", "knowledge_conflict", "knowledge_conflict_audit", "knowledge_search_outbox", "knowledge_candidate_generation", "work_signal_outbox"} {
			if !strings.Contains(table, ".") {
				table = "public." + table
			}
			var ok bool
			e := c.QueryRowContext(ctx, `SELECT bool_and(has_table_privilege(current_user,$1,p)) FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) p`, table).Scan(&ok)
			if e != nil {
				return e
			}
			if !ok {
				return fmt.Errorf("P6运行账号缺少 %s 表权限", table)
			}
		}
		for _, seq := range []string{"knowledge_search_revision", "work_signal_revision", "knowledge_conflict_audit_id_seq"} {
			var ok bool
			if e := c.QueryRowContext(ctx, `SELECT has_sequence_privilege(current_user,$1,'USAGE')`, "public."+seq).Scan(&ok); e != nil {
				return e
			}
			if !ok {
				return errors.New("P6运行账号缺少序列权限")
			}
		}
		return nil
	})
}
