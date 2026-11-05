package knowledge

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

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
