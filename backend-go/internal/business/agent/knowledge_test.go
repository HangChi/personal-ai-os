package agent

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/knowledge"
)

type knowledgeSearchFunc func(context.Context, string, *int) (knowledge.RagResult, error)

func (f knowledgeSearchFunc) Retrieve(ctx context.Context, query string, top *int) (knowledge.RagResult, error) {
	return f(ctx, query, top)
}

func TestKnowledgeToolReadOnlyAuditAndGuardedResults(t *testing.T) {
	for _, status := range []string{"READY", "INSUFFICIENT", "CONFLICT", "CLARIFY", "UNVERIFIED"} {
		t.Run(status, func(t *testing.T) {
			calls := 0
			rag := knowledge.RagResult{Status: status, Hits: []knowledge.Hit{}}
			if status != "INSUFFICIENT" {
				rag.Hits = []knowledge.Hit{{DocumentID: "source", DocumentTitle: "source", Content: "source evidence"}}
			}
			definition := KnowledgeTool(knowledgeSearchFunc(func(_ context.Context, query string, top *int) (knowledge.RagResult, error) {
				calls++
				if query != "source" || top == nil || *top != 1 {
					t.Fatalf("retrieval arguments: %q %v", query, top)
				}
				return rag, nil
			}))
			if definition.Write {
				t.Fatal("read-only knowledge search marked as write")
			}
			audit := &memoryAudit{records: map[string]idempotency.Record{}}
			idem, _ := idempotency.NewService(audit)
			registry, err := NewRegistry(idem, nil, []Definition{definition})
			if err != nil {
				t.Fatal(err)
			}
			builtin, _ := builtinDefinition("searchKnowledge")
			if definitions := registry.Definitions(); len(definitions) != 1 || !reflect.DeepEqual(definitions[0], builtin) {
				t.Fatal("registered schema differs from fixed Java tool definition")
			}
			turn := testTurn()
			call := toolCall(`{"query":"source","topK":1}`)
			call.Function.Name = "searchKnowledge"
			for range 2 {
				result, err := registry.Execute(context.Background(), turn, call)
				if err != nil || result.Status != "OK" || result.ConfirmationID != nil {
					t.Fatal(result, err)
				}
				if rag.Guarded() && result.Message != rag.GuardedAnswer() {
					t.Fatal("guarded evidence was flattened into a certain answer", result)
				}
				if !rag.Guarded() && status == "INSUFFICIENT" && result.Message != "知识库中没有找到相关内容" {
					t.Fatal("empty search became a retryable failure", result)
				}
				if rag.Guarded() {
					data, ok := result.Data.(map[string]any)
					if !ok || data["status"] != status {
						t.Fatal("guard status lost in audited tool result", result)
					}
				}
			}
			if calls != 2 || len(audit.records) != 2 || turn.Budget.HasWrite("searchKnowledge") {
				t.Fatal("read-only calls must be separately executed and audited", calls, len(audit.records))
			}
		})
	}
}

func TestKnowledgeToolValidationFailureAndCancellation(t *testing.T) {
	calls := 0
	definition := KnowledgeTool(knowledgeSearchFunc(func(_ context.Context, _ string, top *int) (knowledge.RagResult, error) {
		calls++
		if top != nil {
			t.Fatal("missing topK must preserve default retrieval limit")
		}
		return knowledge.RagResult{}, errors.New("private upstream details")
	}))
	audit := &memoryAudit{records: map[string]idempotency.Record{}}
	idem, _ := idempotency.NewService(audit)
	registry, err := NewRegistry(idem, nil, []Definition{definition})
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{`{"query":"　 "}`, `{"query":"x","topK":1.5}`} {
		call := toolCall(raw)
		call.Function.Name = "searchKnowledge"
		result, err := registry.Execute(context.Background(), testTurn(), call)
		if err != nil || result.Status != "REJECTED" || calls != 0 {
			t.Fatal("invalid input reached retrieval", result, err, calls)
		}
	}
	call := toolCall(`{"query":"x"}`)
	call.Function.Name = "searchKnowledge"
	result, err := registry.Execute(context.Background(), testTurn(), call)
	if err != nil || result.Status != "ERROR" || strings.Contains(result.Message, "private") || calls != 1 {
		t.Fatal(result, err, calls)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = registry.Execute(ctx, testTurn(), call); !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatal("cancelled call reached retrieval", err, calls)
	}
}

func TestKnowledgeToolRegistersGlobalCitationNumbersAndHonorsBlocks(t *testing.T) {
	for _, status := range []string{"READY", "UNVERIFIED"} {
		d := KnowledgeTool(knowledgeSearchFunc(func(context.Context, string, *int) (knowledge.RagResult, error) {
			return knowledge.RagResult{Status: status, Hits: []knowledge.Hit{{DocumentID: "doc", DocumentTitle: "source", Content: "evidence"}}}, nil
		}))
		turn := testTurn()
		registered := 0
		turn.AddKnowledgeSources = func(h []knowledge.Hit) []knowledge.Citation {
			registered += len(h)
			return []knowledge.Citation{{Index: 7}}
		}
		result, e := d.Action(context.Background(), turn, map[string]any{"query": "evidence"})
		if e != nil || result.Status != "OK" || registered != 1 {
			t.Fatal(result, e, registered)
		}
		if status == "UNVERIFIED" && !strings.Contains(result.Message, "[7]") {
			t.Fatal(result)
		}
		turn.KnowledgeBlocked = true
		result, e = d.Action(context.Background(), turn, map[string]any{"query": "evidence"})
		if e != nil || result.Status != "REJECTED" || registered != 1 {
			t.Fatal(result, e)
		}
		turn.KnowledgeBlocked = false
		if _, e = d.Action(context.Background(), turn, map[string]any{"query": "evidence", "topK": "invalid"}); e == nil {
			t.Fatal("bad topK accepted")
		}
	}
}
