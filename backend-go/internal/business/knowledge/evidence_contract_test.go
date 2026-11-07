package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"reflect"
	"testing"
	"time"
)

func contractHits() []Hit {
	h := twoHits()
	return append(h, Hit{DocumentID: "33333333-3333-4333-8333-333333333333", DocumentTitle: "C", Content: "节假日另行安排，周末正常休息。", MatchedBy: "VECTOR"})
}

func readEvidenceContract(t *testing.T, file string, target any) {
	t.Helper()
	raw, err := os.ReadFile("../../../../testdata/agent-rag/" + file)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, target); err != nil {
		t.Fatal(err)
	}
}

func TestEvidenceReviewSharedJavaGoContract(t *testing.T) {
	var fixture struct {
		Hits  []Hit
		Cases []struct {
			Name                  string
			Response              json.RawMessage
			Valid                 bool
			Sources               []*SourceInfo
			ExpectedRelevant      []int
			ExpectedConflicts     int
			ExpectedClarification string
		}
	}
	readEvidenceContract(t, "evidence-review.json", &fixture)
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			h := contractHits()
			if len(fixture.Hits) != len(h) {
				t.Fatal("shared fixture must supply every source")
			}
			for i := range h {
				h[i].Content = fixture.Hits[i].Content
			}
			if test.Sources != nil {
				for i := range h {
					h[i].Source = test.Sources[i]
				}
			}
			calls := 0
			m := structuredFunc(func(_ context.Context, _ string, payload any, timeout time.Duration) (json.RawMessage, error) {
				calls++
				if timeout != 4*time.Second {
					t.Fatalf("unexpected budget: %s", timeout)
				}
				if payload.(map[string]any)["question"] != "工作制度" {
					t.Fatal(payload)
				}
				return test.Response, nil
			})
			result, err := (&EvidenceReviewer{Model: m}).Review(context.Background(), "工作制度", h, 4*time.Second)
			if (err == nil) != test.Valid {
				t.Fatalf("valid=%v result=%+v error=%v", test.Valid, result, err)
			}
			if calls != 1 {
				t.Fatal(calls)
			}
			if !test.Valid {
				if len(result.Relevant) != 0 || len(result.Conflicts) != 0 {
					t.Fatal("invalid response leaked partial review", result)
				}
				return
			}
			expected := []Hit{}
			for _, i := range test.ExpectedRelevant {
				expected = append(expected, h[i-1])
			}
			if !reflect.DeepEqual(result.Relevant, expected) || len(result.Conflicts) != test.ExpectedConflicts || result.Clarification != test.ExpectedClarification {
				t.Fatalf("got %+v want hits=%+v conflicts=%d clarification=%s", result, expected, test.ExpectedConflicts, test.ExpectedClarification)
			}
			for _, c := range result.Conflicts {
				if !reflect.DeepEqual(c.Left, h[0]) || !reflect.DeepEqual(c.Right, h[1]) || c.LeftQuote != "每周工作五天" || c.RightQuote != "每周工作四天" || c.Reason != "工作天数不同" {
					t.Fatal(c)
				}
			}
		})
	}
}

func TestRestoredConflictScopeSharedJavaGoContract(t *testing.T) {
	var fixture struct {
		Cases []struct {
			Name                                string
			Response                            json.RawMessage
			Valid                               bool
			ExpectedRelevant, ExpectedConflicts []int
		}
	}
	readEvidenceContract(t, "restored-conflict-scope.json", &fixture)
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			h := contractHits()
			conflicts := []Disagreement{{h[0], h[1], "每周工作五天", "每周工作四天", "分歧"}, {h[0], h[2], "每周工作五天", "节假日另行安排", "分歧"}}
			original := Review{Relevant: h[:1]}
			applied := Review{Relevant: h, Conflicts: conflicts, Clarification: "请指定制度版本"}
			calls := 0
			m := structuredFunc(func(_ context.Context, _ string, _ any, timeout time.Duration) (json.RawMessage, error) {
				calls++
				if timeout != 4*time.Second {
					t.Fatal(timeout)
				}
				return test.Response, nil
			})
			result, err := scopeRestoredConflicts(context.Background(), m, "工作制度", original, applied, 4*time.Second)
			if (err == nil) != test.Valid {
				t.Fatalf("valid=%v result=%+v error=%v", test.Valid, result, err)
			}
			if calls != 1 {
				t.Fatal(calls)
			}
			if test.Valid {
				relevant, kept := []Hit{}, []Disagreement{}
				for _, i := range test.ExpectedRelevant {
					relevant = append(relevant, h[i-1])
				}
				for _, i := range test.ExpectedConflicts {
					kept = append(kept, conflicts[i-1])
				}
				if !reflect.DeepEqual(result.Relevant, relevant) || !reflect.DeepEqual(result.Conflicts, kept) || result.Clarification != applied.Clarification {
					t.Fatalf("got %+v want relevant=%+v kept=%+v", result, relevant, kept)
				}
			} else if len(result.Relevant) != 0 || len(result.Conflicts) != 0 {
				t.Fatal("invalid scope leaked partial result", result)
			}
			if !reflect.DeepEqual(applied.Relevant, h) || !reflect.DeepEqual(applied.Conflicts, conflicts) {
				t.Fatal("scope mutated governance input")
			}
		})
	}
}

func TestEvidenceModelFailureAndScopeGuards(t *testing.T) {
	h := contractHits()
	failure := errors.New("synthetic verifier failure")
	m := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) { return nil, failure })
	if _, err := (&EvidenceReviewer{Model: m}).Review(context.Background(), "工作制度", h, time.Second); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	fresh := Disagreement{h[0], h[1], "每周工作五天", "每周工作四天", "分歧"}
	restored := Disagreement{h[0], h[2], "每周工作五天", "节假日另行安排", "分歧"}
	original := Review{Relevant: h[:2], Conflicts: []Disagreement{fresh}}
	applied := Review{Relevant: h, Conflicts: []Disagreement{fresh, restored}}
	if _, err := scopeRestoredConflicts(context.Background(), m, "工作制度", original, applied, time.Second); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	m = structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
		return json.Marshal(map[string]any{"conflicts": []any{map[string]any{"index": 1, "relevant": false, "reason": "与当前问题无关"}}})
	})
	scoped, err := scopeRestoredConflicts(context.Background(), m, "工作制度", original, applied, time.Second)
	if err != nil || !reflect.DeepEqual(scoped.Conflicts, original.Conflicts) || !reflect.DeepEqual(scoped.Relevant, original.Relevant) {
		t.Fatal(scoped, err)
	}
	for _, count := range []int{16, 17} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			rows := []map[string]any{}
			excess := Review{Relevant: h}
			for i := 0; i < count; i++ {
				excess.Conflicts = append(excess.Conflicts, restored)
				rows = append(rows, map[string]any{"index": i + 1, "relevant": true, "reason": "需要保留"})
			}
			calls := 0
			model := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
				calls++
				return json.Marshal(map[string]any{"conflicts": rows})
			})
			_, err := scopeRestoredConflicts(context.Background(), model, "工作制度", original, excess, time.Second)
			if count == 16 && (err != nil || calls != 1) {
				t.Fatal(err, calls)
			}
			if count == 17 && (err == nil || calls != 0) {
				t.Fatal(err, calls)
			}
		})
	}
	if _, err := scopeRestoredConflicts(context.Background(), nil, "工作制度", original, applied, time.Second); err == nil {
		t.Fatal("nil verifier accepted")
	}
}
