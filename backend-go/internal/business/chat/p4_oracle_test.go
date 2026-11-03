package chat

import (
	"encoding/json"
	"fmt"
	"os"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"reflect"
	"testing"
	"time"
)

type p4Oracle struct {
	Inputs struct {
		Budgets []struct {
			Models, Failures int
			Actions          []struct{ Op, Fp, Status string }
		}
		Bindings  []json.RawMessage
		Arguments []struct {
			Kind string
			Text *string
		}
		Usages []struct {
			Prompt, Completion *int
			Text, Model        *string
		}
		Estimates []struct {
			Sample, Reply *string
			Chars         int
		}
	}
	Expected struct {
		Budgets [][]struct {
			Allowed bool
			Calls   int
			Message string
		}
		Bindings    []map[string]any
		Arguments   []map[string]any
		Definitions []map[string]any
		Usages      []struct {
			Text  string
			Usage *struct {
				Model                          *string
				PromptTokens, CompletionTokens int
				Source                         string
			}
		}
		Estimates []struct {
			Model                          *string
			PromptTokens, CompletionTokens int
			Source                         string
		}
	}
}

func TestOriginalJavaP4UsageSnapshotsAndEstimates(t *testing.T) {
	f := readP4Oracle(t)
	if len(f.Inputs.Usages) != 56 || len(f.Inputs.Estimates) != 100 {
		t.Fatal("incomplete Java usage fixture")
	}
	for i, c := range f.Inputs.Usages {
		p, n := 0, 0
		if c.Prompt != nil {
			p = *c.Prompt
		}
		if c.Completion != nil {
			n = *c.Completion
		}
		got := resolveUsage(&model.Usage{PromptTokens: p, CompletionTokens: n}, value(c.Model), "", 0, value(c.Text))
		want := f.Expected.Usages[i]
		if value(c.Text) != want.Text {
			t.Fatal("response text", i, want.Text)
		}
		if want.Usage == nil {
			if got.Source != "ESTIMATED" {
				t.Fatal(i, got)
			}
			continue
		}
		if got.Source != want.Usage.Source || got.Prompt != want.Usage.PromptTokens || got.Completion != want.Usage.CompletionTokens || got.Model != value(want.Usage.Model) {
			t.Fatal(i, got, want.Usage)
		}
	}
	for i, c := range f.Inputs.Estimates {
		got := resolveUsage(nil, "", value(c.Sample), c.Chars, value(c.Reply))
		want := f.Expected.Estimates[i]
		if got.Source != want.Source || got.Prompt != want.PromptTokens || got.Completion != want.CompletionTokens || got.Model != value(want.Model) {
			t.Fatal(i, got, want)
		}
	}
}

func readP4Oracle(t *testing.T) p4Oracle {
	t.Helper()
	data, e := os.ReadFile("testdata/java-p4.json")
	if e != nil {
		t.Fatal(e)
	}
	var v p4Oracle
	if e = json.Unmarshal(data, &v); e != nil {
		t.Fatal(e)
	}
	return v
}
func jsonValue(v any) any {
	raw, _ := json.Marshal(v)
	var out any
	_ = json.Unmarshal(raw, &out)
	return out
}
func TestOriginalJavaP4BudgetsAndArguments(t *testing.T) {
	f := readP4Oracle(t)
	for i, c := range f.Inputs.Budgets {
		b := agent.NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: c.Models, MaxConsecutiveFailures: c.Failures})
		calls := 0
		for j, a := range c.Actions {
			var e error
			switch a.Op {
			case "model":
				e = b.BeforeModel()
				if e == nil {
					calls++
				}
			case "tool":
				e = b.BeforeTool("tool", a.Fp, 0, 0)
			case "record":
				b.Record("tool", a.Fp, agent.Result{Status: a.Status}, false)
			case "write":
				b.Record(a.Fp, "", agent.Result{Status: a.Status}, true)
			}
			expected := f.Expected.Budgets[i][j]
			if (e == nil) != expected.Allowed || calls != expected.Calls {
				t.Fatalf("budget %d action %d: allowed=%t calls=%d want=%+v", i, j, e == nil, calls, expected)
			}
			if e != nil && e.Error() != expected.Message {
				t.Fatal(e, expected.Message)
			}
		}
	}
	for i, c := range f.Inputs.Arguments {
		got := map[string]any{}
		var e error
		if c.Kind == "uuid" {
			got["value"], e = agent.ParseTaskID(value(c.Text), "taskId")
		} else {
			var v *time.Time
			v, e = agent.ParseToolInstant(value(c.Text))
			got["value"] = nil
			if v != nil {
				got["value"] = v.Format(time.RFC3339Nano)
			}
		}
		if e != nil {
			got = map[string]any{"error": e.Error()}
		}
		if !reflect.DeepEqual(got, f.Expected.Arguments[i]) {
			t.Errorf("argument %d %+v got=%v want=%v", i, c, got, f.Expected.Arguments[i])
		}
	}
}
func TestOriginalJavaP4RequestBindings(t *testing.T) {
	f := readP4Oracle(t)
	for i, raw := range f.Inputs.Bindings {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			var obj map[string]json.RawMessage
			_ = json.Unmarshal(raw, &obj)
			r, e := decodeStream(obj)
			got := map[string]any{"bindingError": true}
			if e == nil {
				got = map[string]any{"request": r, "knowledge": r.Knowledge(), "limit": r.Limit(), "thinking": ThinkingBudget(value(r.ThinkingLevel))}
			}
			expected := f.Expected.Bindings[i]
			// Go 用空字符串表示必填正文的 null；二者均在控制器校验中拒绝，不能进入运行请求。
			if request, ok := expected["request"].(map[string]any); ok && request["content"] == nil {
				request["content"] = ""
			}
			if !reflect.DeepEqual(jsonValue(got), expected) {
				t.Fatalf("input %s\ngot=%v\nwant=%v", raw, jsonValue(got), f.Expected.Bindings[i])
			}
		})
	}
}
