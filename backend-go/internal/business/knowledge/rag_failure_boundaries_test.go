package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
)

type failureRecall struct {
	*recallFixture
	hasErr, candidateErr error
}

func (r failureRecall) HasContent(ctx context.Context) (bool, error) {
	return true, r.hasErr
}
func (r failureRecall) Candidates(ctx context.Context, q string, n int) ([]Hit, error) {
	if r.candidateErr != nil && (q == "rewrite" || r.hits == nil) {
		return nil, r.candidateErr
	}
	return r.recallFixture.Candidates(ctx, q, n)
}

type boundaryReranker struct{ err error }

func (r boundaryReranker) Rank(_ context.Context, _ string, h []Hit, _ time.Duration) ([]Hit, map[string]float64, error) {
	return h, map[string]float64{hitKey(h[0]): 0.9}, r.err
}

type boundaryGovernance struct {
	fail  string
	calls []string
}

func (g *boundaryGovernance) mark(stage string) error {
	g.calls = append(g.calls, stage)
	if g.fail == stage {
		return errors.New("governance " + stage)
	}
	return nil
}
func (g *boundaryGovernance) Enrich(_ context.Context, h []Hit) ([]Hit, error) {
	return h, g.mark("enrich")
}
func (g *boundaryGovernance) Expand(_ context.Context, h []Hit) ([]Hit, error) {
	return h, g.mark("expand")
}
func (g *boundaryGovernance) ApplyDecisions(_ context.Context, r Review, _ []Hit, _ *[]string) (Review, error) {
	return r, g.mark("apply")
}
func (g *boundaryGovernance) Record(_ context.Context, _ []Disagreement) error {
	return g.mark("record")
}

func TestRAGGatewayRejectsUnserializableAndUnconfiguredRequests(t *testing.T) {
	for _, runtime := range []*model.Runtime{nil, model.NewRuntime(nil)} {
		g := NewGateway(context.Background(), runtime, nil)
		if _, e := g.Call(context.Background(), "rules", make(chan int), time.Second); e == nil {
			t.Fatal("accepted unsupported payload")
		}
		if _, e := g.Call(context.Background(), "rules", map[string]string{"q": "offline"}, time.Second); e == nil {
			t.Fatal("accepted unconfigured runtime")
		}
		g.Close()
	}
}

func TestRAGPipelineStorageRerankAndGovernanceFailures(t *testing.T) {
	for _, stage := range []string{"has", "candidates", "rewrite-recall", "enrich", "expand", "apply", "record", "rerank", "ok", "empty", "cancel"} {
		t.Run(stage, func(t *testing.T) {
			hits := twoHits()
			recall := failureRecall{recallFixture: &recallFixture{hits: hits}}
			failure := errors.New("recall unavailable")
			if stage == "has" {
				recall.hasErr = failure
			}
			if stage == "candidates" {
				recall.hits = nil
				recall.candidateErr = failure
			}
			if stage == "rewrite-recall" {
				recall.candidateErr = failure
			}
			if stage == "empty" {
				recall.hits = []Hit{{Content: "orphan chunk"}}
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			m := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
				if strings.Contains(rules, "queries") {
					if stage == "cancel" {
						cancel()
					}
					return json.RawMessage(`{"queries":["rewrite"]}`), nil
				}
				return reviewJSON(hits, "", ""), nil
			})
			g := &boundaryGovernance{fail: stage}
			reranker := boundaryReranker{}
			if stage == "rerank" {
				reranker.err = failure
			}
			p := &Pipeline{Enabled: true, Recall: recall, Model: m, Review: &EvidenceReviewer{m}, Governance: g, Reranker: reranker, Stats: NewStats()}
			result, e := p.Retrieve(ctx, "question", nil)
			wantErr := stage == "has" || stage == "candidates" || stage == "enrich" || stage == "cancel"
			if (e != nil) != wantErr {
				t.Fatal(result, e)
			}
			if wantErr {
				return
			}
			if stage == "empty" {
				if result.Status != "INSUFFICIENT" {
					t.Fatal(result)
				}
				return
			}
			want := "READY"
			if stage == "expand" || stage == "apply" || stage == "record" {
				want = "UNVERIFIED"
			}
			if result.Status != want {
				t.Fatal(result)
			}
			if stage == "rewrite-recall" && !strings.Contains(strings.Join(result.Warnings, " "), "部分改写召回失败") {
				t.Fatal(result)
			}
			if stage == "ok" {
				if strings.Join(g.calls, ",") != "enrich,enrich,expand,apply,record" || result.RankingScores[hitKey(hits[0])].Rerank == nil {
					t.Fatal(result, g.calls)
				}
			}
		})
	}
	if answer := (RagResult{Status: "CLARIFY", Clarification: "请指定版本"}).GuardedAnswer(); !strings.Contains(answer, "请指定版本") {
		t.Fatal(answer)
	}
}
