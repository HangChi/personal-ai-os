package knowledge

import (
	"context"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

func TestEvidenceAnswerSharedJavaGoContract(t *testing.T) {
	raw, err := os.ReadFile("../../../../specs/039-evidence-aware-answers/contracts/answer-cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Question      string
		Hits          []Hit
		Disagreements []Disagreement
		Cases         []struct {
			Name               string
			Plan, Verification json.RawMessage
			Prepared           bool
			ExpectedAnswer     string
		}
	}
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, c := range fixture.Cases {
		t.Run(c.Name, func(t *testing.T) {
			calls := 0
			model := structuredFunc(func(_ context.Context, _ string, _ any, timeout time.Duration) (json.RawMessage, error) {
				if timeout <= 0 || timeout > 5*time.Second {
					t.Fatal(timeout)
				}
				calls++
				if calls == 2 && timeout > 3*time.Second {
					t.Fatal(timeout)
				}
				if calls == 1 {
					return c.Plan, nil
				}
				return c.Verification, nil
			})
			r := legacy(fixture.Hits, "独立检索问题")
			r.Status, r.Disagreements = "CONFLICT", fixture.Disagreements
			r.PreparedAnswer = prepareEvidenceAnswer(context.Background(), model, fixture.Question, r, 8*time.Second)
			if (r.PreparedAnswer != "") != c.Prepared || r.GuardedAnswer() != c.ExpectedAnswer {
				t.Fatalf("prepared=%v answer=%s", r.PreparedAnswer != "", r.GuardedAnswer())
			}
			serialized, _ := json.Marshal(r)
			if strings.Contains(string(serialized), "preparedAnswer") || !r.Guarded() {
				t.Fatal(string(serialized))
			}
			if c.Prepared {
				q := &QA{Search: searchFunc(func(context.Context, string, *int) (RagResult, error) { return r, nil })}
				response, e := q.Ask(context.Background(), "问题", nil)
				if e != nil || response.Answered || response.Answer != r.PreparedAnswer || len(response.Citations) != 3 {
					t.Fatal(response, e)
				}
				remapped := r.AnswerWithIndexes([]int{7, 9, 12})
				for _, mark := range []string{"[7]", "[9]", "[12]"} {
					if !strings.Contains(remapped, mark) {
						t.Fatal(remapped)
					}
				}
				for _, mark := range []string{"[1]", "[2]", "[3]"} {
					if strings.Contains(remapped, mark) {
						t.Fatal(remapped)
					}
				}
			}
		})
	}
}

func TestEvidenceAnswerUnverifiedCancelledAndBudgetFailureNeverGenerateFacts(t *testing.T) {
	calls := 0
	m := structuredFunc(func(ctx context.Context, _ string, _ any, _ time.Duration) (json.RawMessage, error) {
		calls++
		<-ctx.Done()
		return nil, ctx.Err()
	})
	r := legacy(twoHits(), "q")
	r.Status = "UNVERIFIED"
	if prepareEvidenceAnswer(context.Background(), m, "q", r, time.Second) != "" || calls != 0 {
		t.Fatal(calls)
	}
	if strings.Contains(r.GuardedAnswer(), "制度版本") || !strings.Contains(r.GuardedAnswer(), "证据核对未完成") {
		t.Fatal(r.GuardedAnswer())
	}
	r.Status = "CONFLICT"
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if prepareEvidenceAnswer(ctx, m, "q", r, time.Second) != "" || calls != 0 {
		t.Fatal(calls)
	}
	if prepareEvidenceAnswer(context.Background(), m, "q", r, 0) != "" || calls != 0 {
		t.Fatal(calls)
	}
	if prepareEvidenceAnswer(context.Background(), m, "q", r, 100*time.Millisecond) != "" || calls != 1 {
		t.Fatal(calls)
	}
	if r.AnswerWithIndexes([]int{1}) != "" || r.AnswerWithIndexes([]int{0, 2}) != "" {
		t.Fatal("invalid remap leaked local indexes")
	}
}
