package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
)

type structuredFunc func(context.Context, string, any, time.Duration) (json.RawMessage, error)

func (f structuredFunc) Call(c context.Context, r string, p any, d time.Duration) (json.RawMessage, error) {
	return f(c, r, p, d)
}

type recallFixture struct {
	hits    []Hit
	calls   int
	byQuery map[string][]Hit
}

func (r *recallFixture) HasContent(context.Context) (bool, error) { return len(r.hits) > 0, nil }
func (r *recallFixture) Candidates(_ context.Context, query string, _ int) ([]Hit, error) {
	r.calls++
	if hits, ok := r.byQuery[query]; ok {
		return hits, nil
	}
	return r.hits, nil
}
func (r *recallFixture) Search(context.Context, string, *int) ([]Hit, error) { return r.hits, nil }
func reviewJSON(hits []Hit, conflicts string, clarification string) json.RawMessage {
	rows := []any{}
	for i := range hits {
		rows = append(rows, map[string]any{"index": i + 1, "grade": "SUPPORTED"})
	}
	if conflicts == "" {
		conflicts = "[]"
	}
	b, _ := json.Marshal(map[string]any{"evidence": rows, "conflicts": json.RawMessage(conflicts), "clarification": clarification})
	return b
}
func twoHits() []Hit {
	return []Hit{{DocumentID: "11111111-1111-4111-8111-111111111111", DocumentTitle: "A", ChunkIndex: 0, Content: "每周工作五天，每天八小时。", MatchedBy: "VECTOR"}, {DocumentID: "22222222-2222-4222-8222-222222222222", DocumentTitle: "B", ChunkIndex: 0, Content: "每周工作四天，每天六小时。", MatchedBy: "VECTOR"}}
}
func TestEvidenceReviewCoverageGroundingPeriodsAndClarification(t *testing.T) {
	hits := twoHits()
	conflict := `[{"left":1,"right":2,"leftQuote":"每周工作五天","rightQuote":"每周工作四天","reason":"同一制度不同说法"}]`
	review, e := ParseReview(reviewJSON(hits, conflict, ""), hits)
	if e != nil || len(review.Conflicts) != 1 || len(review.Relevant) != 2 {
		t.Fatal(review, e)
	}
	for _, raw := range []string{`{}`, `{"evidence":[{"index":1,"grade":"SUPPORTED"}],"conflicts":[]}`, `{"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":1,"grade":"SUPPORTED"}],"conflicts":[]}`, strings.Replace(string(reviewJSON(hits, conflict, "")), "每周工作四天", "不在原文之中", 1)} {
		if _, e := ParseReview([]byte(raw), hits); e == nil {
			t.Fatal(raw)
		}
	}
	until, from := "2025-12-31", "2026-01-01"
	hits[0].Source = &SourceInfo{EffectiveUntil: &until}
	hits[1].Source = &SourceInfo{EffectiveFrom: &from}
	review, e = ParseReview(reviewJSON(hits, conflict, ""), hits)
	if e != nil || len(review.Conflicts) != 0 || review.Clarification == "" {
		t.Fatal(review, e)
	}
}
func TestEvidenceReviewScopesConflictsToTheQuestion(t *testing.T) {
	hits := append(twoHits(), Hit{DocumentID: "33333333-3333-4333-8333-333333333333", DocumentTitle: "Redis 缓存", Content: "商品缓存 TTL 为 720 秒，抖动 0—180 秒，空值缓存 90 秒。"})
	raw := []byte(`{"evidence":[{"index":1,"grade":"IRRELEVANT"},{"index":2,"grade":"IRRELEVANT"},{"index":3,"grade":"SUPPORTED"}],"conflicts":[{"left":1,"right":2,"leftQuote":"每周工作五天","rightQuote":"每周工作四天"}],"clarification":""}`)
	review, err := ParseReview(raw, hits)
	if err != nil || len(review.Conflicts) != 0 || len(review.Relevant) != 1 || review.Relevant[0].DocumentID != hits[2].DocumentID {
		t.Fatal(review, err)
	}
	until, from := "2025-12-31", "2026-01-01"
	hits[0].Source = &SourceInfo{EffectiveUntil: &until}
	hits[1].Source = &SourceInfo{EffectiveFrom: &from}
	review, err = ParseReview(raw, hits)
	if err != nil || review.Clarification != "" {
		t.Fatal(review, err)
	}
	hits[0].Source = nil
	hits[1].Source = nil
	raw = []byte(strings.Replace(string(raw), `"index":1,"grade":"IRRELEVANT"`, `"index":1,"grade":"SUPPORTED"`, 1))
	review, err = ParseReview(raw, hits)
	if err != nil || len(review.Conflicts) != 1 || len(review.Relevant) != 3 {
		t.Fatal(review, err)
	}
}

func TestPipelineReadyConflictTopOneFallbackAndNoContent(t *testing.T) {
	for _, status := range []string{"READY", "CONFLICT", "CLARIFY", "UNVERIFIED"} {
		t.Run(status, func(t *testing.T) {
			hits := twoHits()
			recall := &recallFixture{hits: hits}
			calls := 0
			m := structuredFunc(func(_ context.Context, rules string, _ any, d time.Duration) (json.RawMessage, error) {
				calls++
				if d <= 0 {
					t.Fatal(d)
				}
				switch {
				case strings.Contains(rules, "queries"):
					return json.RawMessage(`{"queries":["同义词"]}`), nil
				case strings.Contains(rules, "order"):
					return json.RawMessage(`{"order":[2,1]}`), nil
				default:
					if status == "UNVERIFIED" {
						return nil, errors.New("timeout")
					}
					conflict, clarify := "", ""
					if status == "CONFLICT" {
						conflict = `[{"left":1,"right":2,"leftQuote":"每周工作四天","rightQuote":"每周工作五天","reason":"不同说法"}]`
					}
					if status == "CLARIFY" {
						clarify = "请说明制度版本"
					}
					ordered := []Hit{hits[1], hits[0]}
					return reviewJSON(ordered, conflict, clarify), nil
				}
			})
			p := &Pipeline{Recall: recall, Model: m, Review: &EvidenceReviewer{m}, Enabled: true, TopK: 4}
			one := 1
			r, e := p.Retrieve(context.Background(), "制度", &one)
			if e != nil || r.Status != status || calls != 3 || recall.calls != 2 {
				t.Fatal(r, e, calls, recall.calls)
			}
			if status == "READY" && len(r.Hits) != 1 {
				t.Fatal(r)
			}
			if status == "CONFLICT" && (len(r.Hits) != 2 || !r.Guarded() || !strings.Contains(r.GuardedAnswer(), "[2]")) {
				t.Fatal(r)
			}
			if status == "UNVERIFIED" && len(r.Warnings) == 0 {
				t.Fatal(r)
			}
		})
	}
	recall := &recallFixture{}
	p := &Pipeline{Recall: recall, Enabled: true}
	r, e := p.Retrieve(context.Background(), "empty", nil)
	if e != nil || r.Status != "INSUFFICIENT" || recall.calls != 0 {
		t.Fatal(r, e)
	}
	if _, e = p.Retrieve(context.Background(), strings.Repeat("x", 2001), nil); e == nil {
		t.Fatal("accepted overlong query")
	}
}

func TestPipelineNoRelevantEvidenceCannotBecomeClarification(t *testing.T) {
	hits := twoHits()
	recall := &recallFixture{hits: hits}
	m := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
		switch {
		case strings.Contains(rules, "queries"):
			return json.Marshal(map[string]any{"queries": []string{"related"}})
		case strings.Contains(rules, "order"):
			return json.Marshal(map[string]any{"order": []int{1, 2}})
		default:
			return json.Marshal(map[string]any{"evidence": []any{map[string]any{"index": 1, "grade": "IRRELEVANT"}, map[string]any{"index": 2, "grade": "IRRELEVANT"}}, "conflicts": []any{}, "clarification": "Which version?"})
		}
	})
	p := &Pipeline{Recall: recall, Model: m, Review: &EvidenceReviewer{m}, Enabled: true}
	result, err := p.Retrieve(context.Background(), "unrelated question", nil)
	if err != nil || result.Status != "INSUFFICIENT" || len(result.Hits) != 0 || result.Clarification != "" || len(result.Warnings) != 0 {
		t.Fatal(result, err)
	}
	answerCalls := 0
	qa := &QA{Search: p, Answer: func(context.Context, model.ChatRequest) (model.ChatResult, error) {
		answerCalls++
		return model.ChatResult{}, errors.New("must not answer without evidence")
	}}
	answer, err := qa.Ask(context.Background(), "unrelated question", nil)
	if err != nil || answer.Answered || len(answer.Citations) != 0 || answerCalls != 0 {
		t.Fatal(answer, err, answerCalls)
	}
}

func TestPipelineRewriteRecoveryAndFailedStages(t *testing.T) {
	for _, scenario := range []string{"rewrite_recovery", "invalid_rerank", "unavailable"} {
		t.Run(scenario, func(t *testing.T) {
			hits := twoHits()
			recall := &recallFixture{hits: hits}
			if scenario == "rewrite_recovery" {
				recall.byQuery = map[string][]Hit{"原问题": {}}
			}
			m := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
				if scenario == "unavailable" {
					return nil, errors.New("model unavailable")
				}
				if strings.Contains(rules, "queries") {
					return json.RawMessage(`{"queries":["住宿限额"]}`), nil
				}
				if strings.Contains(rules, "order") {
					if scenario == "invalid_rerank" {
						return json.RawMessage(`{"order":[99]}`), nil
					}
					return json.RawMessage(`{"order":[1,2]}`), nil
				}
				return reviewJSON(hits, "", ""), nil
			})
			p := &Pipeline{Recall: recall, Model: m, Review: &EvidenceReviewer{m}, Enabled: true, TopK: 4}
			result, e := p.Retrieve(context.Background(), "原问题", nil)
			if e != nil || len(result.Hits) != 2 {
				t.Fatal(result, e)
			}
			if scenario == "unavailable" {
				if result.Status != "UNVERIFIED" || !result.Guarded() || len(result.Queries) != 1 {
					t.Fatal(result)
				}
			} else if result.Status != "READY" || len(result.Queries) != 2 {
				t.Fatal(result)
			}
			if scenario == "invalid_rerank" && len(result.Warnings) == 0 {
				t.Fatal("invalid rerank lost its warning")
			}
		})
	}
}

type searchFunc func(context.Context, string, *int) (RagResult, error)

func (f searchFunc) Retrieve(c context.Context, q string, k *int) (RagResult, error) {
	return f(c, q, k)
}

func TestQAActualCitationNumbersGuardAndEmptyNoAnswerCall(t *testing.T) {
	hits := twoHits()
	hits[1].Source = &SourceInfo{Version: "2026"}
	calls := 0
	q := &QA{Search: searchFunc(func(context.Context, string, *int) (RagResult, error) { return legacy(hits, "q"), nil }), Answer: func(_ context.Context, r model.ChatRequest) (model.ChatResult, error) {
		calls++
		if len(r.Tools) != 0 || !strings.Contains(r.Messages[1].Content, hits[1].Content) {
			t.Fatal(r)
		}
		return model.ChatResult{Content: "结论[2][2][999999999999999999999999999999]"}, nil
	}}
	response, e := q.Ask(context.Background(), " question ", nil)
	if e != nil || !response.Answered || len(response.Citations) != 1 || response.Citations[0].Index != 2 || response.Question != "question" {
		t.Fatal(response, e)
	}
	if citation := response.Citations[0]; citation.Source != hits[1].Source || citation.MatchedBy != hits[1].MatchedBy {
		t.Fatal(citation)
	}
	if got := BuildCitations("没有编号", hits); len(got) != 2 {
		t.Fatal(got)
	}
	for _, status := range []string{"INSUFFICIENT", "CONFLICT", "UNVERIFIED", "CLARIFY"} {
		q.Search = searchFunc(func(context.Context, string, *int) (RagResult, error) {
			r := legacy([]Hit{}, "q")
			r.Status = status
			if r.Guarded() {
				r.Hits = hits
			}
			return r, nil
		})
		response, e = q.Ask(context.Background(), "q", nil)
		if e != nil || response.Answered || calls != 1 {
			t.Fatal(response, e, calls)
		}
	}
}
func TestCrossEncoderCohereDashscopeAndInvalidRanks(t *testing.T) {
	for _, protocol := range []string{"cohere", "dashscope"} {
		t.Run(protocol, func(t *testing.T) {
			invalid := false
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var p map[string]any
				_ = json.NewDecoder(r.Body).Decode(&p)
				if r.Header.Get("Authorization") != "Bearer fixture-key" {
					t.Error("auth")
				}
				if protocol == "dashscope" && p["input"] == nil {
					t.Error(p)
				}
				rows := `[{"index":0,"relevance_score":0.3},{"index":1,"relevance_score":0.9}]`
				if invalid {
					rows = `[{"index":0,"relevance_score":0.3},{"index":0,"relevance_score":0.9}]`
				}
				if protocol == "dashscope" {
					fmt.Fprint(w, `{"output":{"results":`+rows+`}}`)
				} else {
					fmt.Fprint(w, `{"results":`+rows+`}`)
				}
			}))
			defer server.Close()
			ranker, e := NewCrossEncoder(server.URL, "fixture-key", "test", protocol, nil)
			if e != nil {
				t.Fatal(e)
			}
			hits := twoHits()
			ordered, scores, e := ranker.Rank(context.Background(), "q", hits, time.Second)
			if e != nil || ordered[0].DocumentID != hits[1].DocumentID || len(scores) != 2 || ordered[0].Score != nil {
				t.Fatal(ordered, scores, e)
			}
			invalid = true
			if _, _, e = ranker.Rank(context.Background(), "q", hits, time.Second); e == nil {
				t.Fatal("accepted duplicate")
			}
		})
	}
}
