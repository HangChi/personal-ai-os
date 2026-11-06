package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestConversationActionRoutesWithoutDocumentGuard(t *testing.T) {
	recall := &recallFixture{hits: twoHits()}
	p := &Pipeline{Enabled: true, Recall: recall, Budget: 5 * time.Second, Model: structuredFunc(func(ctx context.Context, rules string, input any, timeout time.Duration) (json.RawMessage, error) {
		payload := input.(map[string]any)
		history := payload["history"].([]ConversationMessage)
		if len(history) != 6 || chars(history[5].Content) != 600 || payload["query"] != "改成八点半" || timeout > 4*time.Second || timeout <= 0 {
			t.Fatal(payload, timeout)
		}
		if !strings.Contains(rules, "不能作为知识证据") {
			t.Fatal(rules)
		}
		return []byte(`{"retrieve":false,"query":""}`), nil
	})}
	history := make([]ConversationMessage, 12)
	for i := range history {
		history[i] = ConversationMessage{Role: "ASSISTANT", Content: strings.Repeat("已创建双选会08:00", 100)}
	}
	result, err := p.RetrieveConversation(context.Background(), "改成八点半", history, nil)
	if err != nil || result.Guarded() || len(result.Hits) != 0 || recall.calls != 0 {
		t.Fatal(result, err, recall.calls)
	}
}

func TestConversationKnowledgeFollowupRetainsConflictGuard(t *testing.T) {
	hits := twoHits()
	recall := &recallFixture{hits: hits}
	calls := 0
	m := structuredFunc(func(ctx context.Context, rules string, input any, timeout time.Duration) (json.RawMessage, error) {
		calls++
		switch calls {
		case 1:
			return []byte(`{"retrieve":true,"query":"试用期住宿报销限额"}`), nil
		case 2:
			return []byte(`{"queries":[]}`), nil
		case 3:
			return []byte(`{"order":[1,2]}`), nil
		default:
			return reviewJSON(hits, `[{"left":1,"right":2,"leftQuote":"每周工作五天","rightQuote":"每周工作四天"}]`, ""), nil
		}
	})
	p := &Pipeline{Enabled: true, Recall: recall, Model: m, Review: &EvidenceReviewer{Model: m}}
	result, err := p.RetrieveConversation(context.Background(), "那试用期呢", []ConversationMessage{{"USER", "住宿报销标准是多少"}}, nil)
	if err != nil || result.Status != "CONFLICT" || len(result.Hits) != 2 || result.Queries[0] != "试用期住宿报销限额" {
		t.Fatal(result, err)
	}
}

func TestConversationInvalidRouteAndModelFailureReturnError(t *testing.T) {
	for _, raw := range []string{`{}`, `{"retrieve":"false","query":""}`, `{"retrieve":true,"query":""}`, `{"retrieve":false,"query":"任意内容"}`, `{"retrieve":null,"query":""}`, `{"retrieve":false,"query":null}`, "model-failed"} {
		t.Run(raw, func(t *testing.T) {
			recall := &recallFixture{hits: twoHits()}
			p := &Pipeline{Enabled: true, Recall: recall, Model: structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
				if raw == "model-failed" {
					return nil, errors.New("timeout")
				}
				return []byte(raw), nil
			})}
			if _, err := p.RetrieveConversation(context.Background(), "改成八点半", nil, nil); err == nil || recall.calls != 0 {
				t.Fatal(err, recall.calls)
			}
		})
	}
}

func TestConversationEmptyLibrarySkipsModel(t *testing.T) {
	p := &Pipeline{Enabled: true, Recall: &recallFixture{}}
	result, err := p.RetrieveConversation(context.Background(), "明天参加双选会", nil, nil)
	if err != nil || result.Guarded() {
		t.Fatal(result, err)
	}
}

func TestConversationDisabledPipelinePreservesLegacyRetrieval(t *testing.T) {
	p := &Pipeline{Enabled: false, Recall: &recallFixture{hits: twoHits()}}
	result, err := p.RetrieveConversation(context.Background(), "制度", nil, nil)
	if err != nil || len(result.Hits) != 2 || result.Guarded() {
		t.Fatal(result, err)
	}
}

func TestConversationPartialAnswerKeepsOriginalQuestionAndAllRelevantEvidence(t *testing.T) {
	h := append(twoHits(), Hit{DocumentID: "33333333-3333-4333-8333-333333333333", DocumentTitle: "缓存规范", Content: "商品缓存 TTL 为 720 秒。"})
	original := "那两个标准呢，顺便说明缓存 TTL"
	m := structuredFunc(func(_ context.Context, rules string, payload any, _ time.Duration) (json.RawMessage, error) {
		switch {
		case strings.HasPrefix(rules, "判断本轮"):
			return json.RawMessage(`{"retrieve":true,"query":"工作制度与商品缓存 TTL"}`), nil
		case strings.Contains(rules, "queries"):
			return json.RawMessage(`{"queries":[]}`), nil
		case strings.Contains(rules, "order"):
			return json.RawMessage(`{"order":[1,2,3]}`), nil
		case strings.HasPrefix(rules, "将 question"):
			data := payload.(map[string]any)
			if data["question"] != original || len(data["sources"].([]map[string]any)) != 3 {
				t.Fatal(data)
			}
			if _, ok := data["history"]; ok {
				t.Fatal("history became evidence")
			}
			return json.RawMessage(`{"parts":[{"topic":"缓存 TTL","status":"SUPPORTED","text":"商品缓存 TTL 为 720 秒。","evidence":[{"index":3,"quote":"商品缓存 TTL 为 720 秒"}]}]}`), nil
		case strings.HasPrefix(rules, "独立核对"):
			return json.RawMessage(`{"complete":true,"conflictsRespected":true,"parts":[{"index":1,"supported":true}]}`), nil
		default:
			return reviewJSON(h, `[{"left":1,"right":2,"leftQuote":"每周工作五天","rightQuote":"每周工作四天","reason":"工作天数不同"}]`, ""), nil
		}
	})
	p := &Pipeline{Recall: &recallFixture{hits: h}, Model: m, Review: &EvidenceReviewer{m}, Enabled: true}
	one := 1
	r, err := p.RetrieveConversation(context.Background(), original, nil, &one)
	if err != nil || len(r.Hits) != 3 || !r.Guarded() || r.PreparedAnswer == "" || !strings.Contains(r.PreparedAnswer, "720 秒。 [3]") || r.Stages[len(r.Stages)-1].Status != "OK" {
		t.Fatal(r, err)
	}
}
