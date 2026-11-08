package knowledge

import (
	"context"
	"encoding/json"
	"fmt"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

func TestQueryRewriteConfiguration(t *testing.T) {
	for _, tc := range []struct {
		name             string
		options          config.PropertyOptions
		enabled, invalid bool
	}{
		{name: "default", enabled: true},
		{name: "dotenv off", options: config.PropertyOptions{DotEnv: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "false"}}},
		{name: "environment on", options: config.PropertyOptions{System: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "true"}}, enabled: true},
		{name: "environment wins dotenv", options: config.PropertyOptions{System: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "false"}, DotEnv: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "true"}}},
		{name: "explicit property", options: config.PropertyOptions{Overrides: map[string]string{"app.knowledge.rag.query-rewrite-enabled": "false"}, System: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "true"}}},
		{name: "invalid", options: config.PropertyOptions{DotEnv: map[string]string{"RAG_QUERY_REWRITE_ENABLED": "invalid"}}, invalid: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p, err := config.LoadBaselineProperties(tc.options)
			if err != nil {
				t.Fatal(err)
			}
			enabled, err := queryRewriteEnabled(p)
			if (err != nil) != tc.invalid || enabled != tc.enabled {
				t.Fatalf("enabled=%v err=%v", enabled, err)
			}
		})
	}
}

func TestQueryRewriteSwitchPreservesDownstreamStages(t *testing.T) {
	for _, disabled := range []bool{false, true} {
		for _, empty := range []bool{false, true} {
			t.Run(fmt.Sprintf("disabled=%v/empty=%v", disabled, empty), func(t *testing.T) {
				hits := twoHits()
				initial := hits
				if empty {
					initial = []Hit{}
				}
				recall := &recallFixture{hits: hits, byQuery: map[string][]Hit{"question": initial}}
				rewrites, ranks, reviews := 0, 0, 0
				m := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
					switch {
					case strings.HasPrefix(rules, "保持原问题"):
						rewrites++
						return json.RawMessage(`{"queries":["expanded"]}`), nil
					case strings.HasPrefix(rules, "对候选"):
						ranks++
						return json.RawMessage(`{"order":[2,1]}`), nil
					default:
						reviews++
						return reviewJSON(hits, "", ""), nil
					}
				})
				p := &Pipeline{Recall: recall, Model: m, Review: &EvidenceReviewer{m}, Enabled: true, QueryRewriteDisabled: disabled, TopK: 4}
				result, err := p.Retrieve(context.Background(), "question", nil)
				if err != nil {
					t.Fatal(err)
				}
				if disabled {
					if rewrites != 0 || recall.calls != 1 || len(result.Queries) != 1 || result.Stages[1].Status != "SKIPPED" {
						t.Fatal(result, rewrites, recall.calls)
					}
				} else if rewrites != 1 || recall.calls != 2 || len(result.Queries) != 2 {
					t.Fatal(result, rewrites, recall.calls)
				}
				if disabled && empty {
					if ranks != 0 || reviews != 0 || len(result.Hits) != 0 {
						t.Fatal(result, ranks, reviews)
					}
				} else if ranks != 1 || reviews != 1 || result.Status != "READY" || result.Hits[0].DocumentID != hits[1].DocumentID {
					t.Fatal(result, ranks, reviews)
				}
				if len(result.Warnings) != 0 {
					t.Fatal(result.Warnings)
				}
			})
		}
	}
}
