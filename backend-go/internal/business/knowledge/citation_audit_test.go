package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestCitationAuditSharedJavaGoContract(t *testing.T) {
	raw, err := os.ReadFile("../../../../testdata/agent-rag/citation-audit.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Answer    string
		Citations []Citation
		Cases     []struct {
			Name, ExpectedStatus string
			Answer               *string
			Citations            []Citation
			Response             json.RawMessage
		}
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			answer, citations := fixture.Answer, fixture.Citations
			if test.Answer != nil {
				answer = *test.Answer
			}
			if test.Citations != nil {
				citations = test.Citations
			}
			calls := 0
			model := structuredFunc(func(_ context.Context, _ string, _ any, timeout time.Duration) (json.RawMessage, error) {
				calls++
				if timeout != 5*time.Second {
					t.Fatal(timeout)
				}
				return test.Response, nil
			})
			result := auditCitations(context.Background(), model, answer, citations)
			if result.Status != test.ExpectedStatus || calls != 1 {
				t.Fatal(result, calls)
			}
			if result.Status == "CHECKED" {
				var expected struct{ Claims []CitationCheck }
				if err := json.Unmarshal(test.Response, &expected); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(result.Claims, expected.Claims) {
					t.Fatalf("got %#v want %#v", result.Claims, expected.Claims)
				}
			} else if len(result.Claims) != 0 {
				t.Fatalf("rejected response leaked partial claims: %+v", result)
			}
		})
	}
}

func TestCitationAuditInputLimitsAndVerifierFailure(t *testing.T) {
	for _, answer := range []string{"", " \n\t", strings.Repeat("😀", 6001)} {
		model := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
			t.Fatal("invalid input spent model budget")
			return nil, nil
		})
		if result := auditCitations(context.Background(), model, answer, nil); result.Status != "UNVERIFIED" || len(result.Claims) != 0 {
			t.Fatal(result)
		}
	}
	answer := strings.Repeat("😀", 5996) + " [1]"
	content := strings.Repeat("😀", 1600) + "不应发送的尾部"
	calls := 0
	model := structuredFunc(func(_ context.Context, _ string, payload any, timeout time.Duration) (json.RawMessage, error) {
		calls++
		input := payload.(map[string]any)
		if input["answer"] != answer || input["evidence"].(map[int]string)[1] != strings.Repeat("😀", 1600) || timeout != 5*time.Second {
			t.Fatal(payload, timeout)
		}
		return json.Marshal(map[string]any{"claims": []CitationCheck{{Claim: "😀 [1]", Status: "SUPPORTED", Index: 1, Quote: "😀😀😀😀"}}})
	})
	if result := auditCitations(context.Background(), model, answer, []Citation{{Index: 1, Content: content}}); result.Status != "CHECKED" || calls != 1 {
		t.Fatal(result, calls)
	}
	for _, raw := range []string{"{broken", "null"} {
		bad := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
			return json.RawMessage(raw), nil
		})
		if result := auditCitations(context.Background(), bad, "answer", nil); result.Status != "UNVERIFIED" || len(result.Claims) != 0 {
			t.Fatal(result)
		}
	}
	failed := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
		return nil, errors.New("synthetic timeout")
	})
	if result := auditCitations(context.Background(), failed, "answer", nil); result.Status != "UNVERIFIED" || len(result.Claims) != 0 {
		t.Fatal(result)
	}
}

func TestCitationAuditRejectsFabricatedEvidence(t *testing.T) {
	citations := []Citation{{Index: 1, Content: "报销限额每天五百元。"}}
	answer := "报销每天五百元 [1]。"
	for _, test := range []struct{ quote, status string }{
		{"每天五百元", "CHECKED"}, {"每天八百元", "UNVERIFIED"},
	} {
		model := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
			return json.Marshal(map[string]any{"claims": []CitationCheck{{Claim: answer, Status: "SUPPORTED", Index: 1, Quote: test.quote}}})
		})
		if result := auditCitations(context.Background(), model, answer, citations); result.Status != test.status {
			t.Fatal(result)
		}
	}
}
