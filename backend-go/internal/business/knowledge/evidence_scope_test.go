package knowledge

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestRestoredConflictRequiresCompleteCurrentQuestionScope(t *testing.T) {
	h := twoHits()
	c := Disagreement{h[0], h[1], "每周工作五天", "每周工作四天", "工作天数不同"}
	original := Review{Relevant: h[:1]}
	applied := Review{Relevant: h, Conflicts: []Disagreement{c}}
	for _, test := range []struct {
		raw         string
		valid, keep bool
	}{
		{`{"conflicts":[{"index":1,"relevant":false,"reason":"本轮仅问另一事实"}]}`, true, false},
		{`{"conflicts":[{"index":1,"relevant":true,"reason":"本轮询问工作天数"}]}`, true, true},
		{`{}`, false, false}, {`{"conflicts":[]}`, false, false},
		{`{"conflicts":[{"index":1,"relevant":"false","reason":"无关"}]}`, false, false},
		{`{"conflicts":[{"index":2,"relevant":false,"reason":"无关"}]}`, false, false},
	} {
		m := structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
			return json.RawMessage(test.raw), nil
		})
		scoped, err := scopeRestoredConflicts(context.Background(), m, "仅问另一个事实", original, applied, 4*time.Second)
		if (err == nil) != test.valid {
			t.Fatal(test.raw, err)
		}
		if test.valid && ((len(scoped.Conflicts) > 0) != test.keep || (test.keep && len(scoped.Relevant) != 2) || (!test.keep && len(scoped.Relevant) != 1)) {
			t.Fatal(scoped)
		}
		if len(applied.Conflicts) != 1 {
			t.Fatal("mutated prior governance result")
		}
	}
	if scoped, err := scopeRestoredConflicts(context.Background(), nil, "工作天数", applied, applied, time.Second); err != nil || len(scoped.Conflicts) != 1 {
		t.Fatal(scoped, err)
	}
}
