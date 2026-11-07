package knowledge

import (
	"context"
	"io"
	"log/slog"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"strings"
	"testing"
	"time"
)

func TestKnowledgeModuleRejectsInvalidConfigurationWithoutPartialModule(t *testing.T) {
	for _, tc := range []struct {
		key, value   string
		beforeSchema bool
	}{
		{"app.knowledge.embedding-dimensions", "invalid", true},
		{"spring.ai.vectorstore.pgvector.schema-name", "bad schema", true},
		{"spring.ai.vectorstore.pgvector.table-name", "bad table", true},
		{"app.knowledge.rag.evidence-timeout-ms", "0", false},
		{"app.knowledge.rag.reranker.enabled", "perhaps", false},
		{"app.knowledge.search.enabled", "perhaps", false},
		{"app.knowledge.candidate-cache.enabled", "perhaps", false},
		{"app.redis.enabled", "perhaps", false},
		{"app.knowledge.rag.citation-audit-enabled", "perhaps", false},
		{"app.knowledge.rag.ingestion-review-enabled", "perhaps", false},
		{"app.knowledge.jobs.enabled", "perhaps", false},
	} {
		t.Run(tc.key, func(t *testing.T) {
			overrides := map[string]string{"app.knowledge.embedding-dimensions": "2", "app.rabbitmq.enabled": "false", "app.knowledge.candidate-cache.enabled": "false", "app.redis.enabled": "false", tc.key: tc.value}
			props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			steps := moduleSchemaSteps()
			if tc.beforeSchema {
				steps = nil
			}
			store := contractStore(t, steps...)
			locks, e := coordination.New(store.Pool)
			if e != nil {
				t.Fatal(e)
			}
			defer locks.Close(context.Background())
			m, e := OpenModule(context.Background(), ModuleOptions{Pool: store.Pool, Properties: props, Models: &model.Module{Runtime: model.NewRuntime(nil)}, Coordinator: locks, Role: config.API, Acquire: func(context.Context) (func(), error) { return func() {}, nil }, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
			if e == nil || m != nil {
				if m != nil {
					m.Close()
				}
				t.Fatalf("bad configuration accepted: %s %v", tc.key, e)
			}
		})
	}
}

func TestKnowledgeModuleUnresolvedPropertiesFailAtTheirBoundary(t *testing.T) {
	for _, key := range []string{"app.knowledge.top-k", "spring.ai.vectorstore.pgvector.schema-name", "spring.ai.vectorstore.pgvector.table-name", "app.knowledge.rag.enabled", "app.knowledge.rag.budget-ms", "app.knowledge.rag.evidence-timeout-ms", "app.knowledge.rag.reranker.enabled", "app.knowledge.search.enabled", "app.knowledge.candidate-cache.enabled", "app.redis.enabled", "app.knowledge.rag.citation-audit-enabled"} {
		t.Run(key, func(t *testing.T) {
			overrides := map[string]string{"app.knowledge.embedding-dimensions": "2", "app.rabbitmq.enabled": "false", "app.knowledge.candidate-cache.enabled": "false", "app.redis.enabled": "false", key: "$" + "{contract.unresolved.property}"}
			props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			steps := moduleSchemaSteps()
			if key == "app.knowledge.top-k" || strings.HasPrefix(key, "spring.ai.vectorstore") {
				steps = nil
			}
			s := contractStore(t, steps...)
			locks, e := coordination.New(s.Pool)
			if e != nil {
				t.Fatal(e)
			}
			defer locks.Close(context.Background())
			m, e := OpenModule(context.Background(), ModuleOptions{Pool: s.Pool, Properties: props, Models: &model.Module{Runtime: model.NewRuntime(nil)}, Coordinator: locks, Role: config.API, Acquire: func(context.Context) (func(), error) { return func() {}, nil }, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
			if e == nil || m != nil {
				if m != nil {
					m.Close()
				}
				t.Fatal("unresolved property accepted", key, e)
			}
		})
	}
}

// A later invalid worker interval must cancel and join an already registered worker.
// Acquire deliberately grants no capacity, so no application SQL is run by that worker.
func TestKnowledgeModuleCleansUpWorkersOnLaterStartupFailure(t *testing.T) {
	for attempt := 0; attempt < 25; attempt++ {
		props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{
			"app.knowledge.embedding-dimensions": "2", "app.rabbitmq.enabled": "false",
			"app.knowledge.candidate-cache.enabled": "false", "app.redis.enabled": "false",
			"app.knowledge.jobs.poll-ms": "1", "app.knowledge.jobs.cleanup-ms": "invalid",
		}})
		if e != nil {
			t.Fatal(e)
		}
		store := contractStore(t, moduleSchemaSteps()...)
		locks, e := coordination.New(store.Pool)
		if e != nil {
			t.Fatal(e)
		}
		done := make(chan struct{})
		go func() {
			defer close(done)
			defer locks.Close(context.Background())
			m, e := OpenModule(context.Background(), ModuleOptions{Pool: store.Pool, Properties: props,
				Models: &model.Module{Runtime: model.NewRuntime(nil)}, Coordinator: locks, Role: config.Index,
				Acquire: func(context.Context) (func(), error) { return nil, nil }, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
			if e == nil || m != nil {
				if m != nil {
					m.Close()
				}
				t.Errorf("partial module escaped: %v", e)
			}
		}()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("failed assembly did not join worker")
		}
	}
}
