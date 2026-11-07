package agent

import (
	"context"
	"errors"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
)

type boundaryRecorder struct{}

func (boundaryRecorder) Success(context.Context, string, string, string) error { return nil }
func (boundaryRecorder) Failure(context.Context, string, string, string) (string, error) {
	return "先核对执行状态", nil
}

func TestRegistryRejectsInvalidDefinitionsAndMissingExecutionIdentity(t *testing.T) {
	idem, _ := idempotency.NewService(&memoryAudit{records: map[string]idempotency.Record{}})
	d := Definition{Model: model.Tool{Name: "write"}, Action: func(context.Context, *Turn, map[string]any) (Result, error) { return OK("ok", nil), nil }}
	if _, e := NewRegistry(nil, nil, nil); e == nil {
		t.Fatal("missing audit accepted")
	}
	for _, defs := range [][]Definition{{{}}, {d, d}} {
		if _, e := NewRegistry(idem, nil, defs); e == nil {
			t.Fatal("invalid definitions accepted")
		}
	}
	r, _ := NewRegistry(idem, nil, []Definition{d})
	for _, turn := range []*Turn{nil, {}} {
		if _, e := r.Execute(context.Background(), turn, toolCall(`{}`)); e == nil {
			t.Fatal("missing identity accepted")
		}
	}
	if safeMessage(errors.New("private")) != "工具参数无效" {
		t.Fatal("private error disclosed")
	}
}

func TestToolFailuresAreAuditedWithoutReplayingSideEffects(t *testing.T) {
	for _, mode := range []string{"trailing", "normalize", "digest", "panic", "status", "marshal", "yield", "cancel", "deadline", "hint"} {
		t.Run(mode, func(t *testing.T) {
			idem, _ := idempotency.NewService(&memoryAudit{records: map[string]idempotency.Record{}})
			actionCalls := 0
			d := Definition{Keys: []string{"value"}, Model: model.Tool{Name: "write", Parameters: ObjectSchema(map[string]any{"value": StringSchema()})}, Action: func(context.Context, *Turn, map[string]any) (Result, error) {
				actionCalls++
				switch mode {
				case "panic":
					panic("private")
				case "status":
					return Result{Status: "invalid"}, nil
				case "marshal":
					return OK("bad data", make(chan int)), nil
				case "yield":
					return Result{}, ErrYield
				case "cancel":
					return Result{}, context.Canceled
				case "deadline":
					return Result{}, context.DeadlineExceeded
				case "hint":
					return Result{}, errors.New("private")
				}
				return OK("ok", nil), nil
			}}
			if mode == "normalize" {
				d.Normalize = func(map[string]any) (map[string]any, error) { return nil, errors.New("private") }
			}
			if mode == "digest" {
				d.BindArguments = func(string) (map[string]any, error) { return map[string]any{"value": make(chan int)}, nil }
			}
			r, e := NewRegistry(idem, boundaryRecorder{}, []Definition{d})
			if e != nil {
				t.Fatal(e)
			}
			raw := `{}`
			if mode == "trailing" {
				raw = `{} {}`
			}
			result, e := r.Execute(context.Background(), testTurn(), toolCall(raw))
			wantErr := mode == "digest" || mode == "yield" || mode == "cancel" || mode == "deadline"
			if (e != nil) != wantErr {
				t.Fatal(result, e)
			}
			if mode == "trailing" || mode == "normalize" || mode == "digest" {
				if actionCalls != 0 {
					t.Fatal("invalid action ran")
				}
			}
			if mode == "hint" && !strings.Contains(result.Message, "先核对执行状态") {
				t.Fatal(result)
			}
		})
	}
}

func TestNestedToolArgumentTypesAndUnsupportedDigestValues(t *testing.T) {
	if _, e := ArgumentsDigest(map[string]any{"value": make(chan int)}, []string{"value"}); e == nil {
		t.Fatal("unsupported value accepted")
	}
	for _, items := range []struct {
		schema map[string]any
		value  any
	}{
		{map[string]any{"type": "string"}, []any{1}},
		{ObjectSchema(map[string]any{"name": StringSchema()}), []any{"wrong"}},
		{ObjectSchema(map[string]any{"name": StringSchema()}), []any{map[string]any{"name": 1}}},
	} {
		schema := ObjectSchema(map[string]any{"items": map[string]any{"type": "array", "items": items.schema}})
		if validateTypes(map[string]any{"items": items.value}, schema) == nil {
			t.Fatal("invalid list accepted")
		}
	}
	if e := validateTypes(map[string]any{"unknown": true, "nullable": nil}, ObjectSchema(nil)); e != nil {
		t.Fatal(e)
	}
}
