package chat

import (
	"context"
	"errors"
	"reflect"
	"testing"
)

func TestRetrievalDegradationIsNonterminal(t *testing.T) {
	for _, mode := range []string{"legacy", "evidence", "missing", "zero", "hits"} {
		t.Run(mode, func(t *testing.T) {
			engine := &Engine{}
			failure := errors.New("private upstream detail")
			switch mode {
			case "legacy":
				engine.Retrieve = func(context.Context, string, int) ([]Citation, error) { return nil, failure }
			case "evidence":
				engine.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
					return RetrievedEvidence{Guarded: true, Emit: true, Answer: "stale"}, failure
				}
			case "zero", "hits":
				engine.Retrieve = func(context.Context, string, int) ([]Citation, error) {
					if mode == "hits" {
						return []Citation{{DocumentID: "d"}}, nil
					}
					return nil, nil
				}
			}
			var names []string
			buffer := NewEventBuffer(func(name string, payload any) error {
				names = append(names, name)
				if name == "warning" {
					want := map[string]string{"code": "KNOWLEDGE_RETRIEVAL_FAILED", "message": "知识库检索失败，本轮未使用知识库回答"}
					if !reflect.DeepEqual(payload, want) {
						t.Fatalf("warning = %#v", payload)
					}
				}
				return nil
			})
			defer buffer.Close()
			evidence, err := engine.retrieveForPrompt(context.Background(), "question", 4, buffer.Publish)
			if err != nil {
				t.Fatal(err)
			}
			want := []string{"warning", "delta", "done"}
			if mode == "zero" || mode == "hits" {
				want = []string{"delta", "done"}
			} else if len(evidence.Citations) != 0 || evidence.Guarded || evidence.Emit || evidence.Answer != "" {
				t.Fatal("failed retrieval retained stale evidence", evidence)
			}
			if mode == "hits" && len(evidence.Citations) != 1 {
				t.Fatal(evidence)
			}
			if err := buffer.Publish("delta", map[string]string{"content": "fallback answer"}); err != nil {
				t.Fatal(err)
			}
			if err := buffer.Publish("done", map[string]string{"content": "fallback answer"}); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(names, want) {
				t.Fatal(names)
			}
		})
	}
}

func TestRetrievalCancellationAndWarningDeliveryFailurePropagate(t *testing.T) {
	engine := &Engine{}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := engine.retrieveForPrompt(ctx, "q", 4, func(string, any) error { t.Fatal("published after cancellation"); return nil }); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	failure := errors.New("publisher unavailable")
	if _, err := engine.retrieveForPrompt(context.Background(), "q", 4, func(string, any) error { return failure }); !errors.Is(err, failure) {
		t.Fatal(err)
	}
}
