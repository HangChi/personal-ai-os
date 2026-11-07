//go:build integration

package chat

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"reflect"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/platform/api"
)

// 用真实事务验证与 Java 共用的计划状态契约；模型入口不应被调用。
func TestPostgresPlanSharedJavaGoTransitions(t *testing.T) {
	raw, err := os.ReadFile("../../../../testdata/agent-rag/plan-transitions.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Name, Action, Note, ExpectedPlanStatus         string
			States, ExpectedStates                         []string
			Seq, ExpectedError                             int
			ToolResult, ExpectedReason, ExpectedToolResult *string
			Idempotent                                     bool
		}
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	f := newExecutionFixture(t, func(http.ResponseWriter, *http.Request) { t.Error("plan transition unexpectedly called a model") })
	ctx, plans := context.Background(), f.module.Engine.Plans
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			conversation := fixtureSession(t, f.module.Service, test.Name).ConversationID
			if _, err := plans.Create(ctx, conversation, "共享状态机契约", []string{"第一步", "第二步"}, false); err != nil {
				t.Fatal(err)
			}
			for i, state := range test.States {
				switch state {
				case "PENDING":
					continue
				case "RUNNING", "FAILED":
					if _, err := plans.Change(ctx, conversation, i+1, "start", ""); err != nil {
						t.Fatal(err)
					}
					if state == "FAILED" {
						if _, err := plans.Change(ctx, conversation, i+1, "fail", "初始失败"); err != nil {
							t.Fatal(err)
						}
					}
				case "DONE":
					if _, err := plans.Change(ctx, conversation, i+1, "complete", "初始结论"); err != nil {
						t.Fatal(err)
					}
				case "SKIPPED":
					if _, err := plans.Change(ctx, conversation, i+1, "skip", "初始跳过"); err != nil {
						t.Fatal(err)
					}
				default:
					t.Fatalf("unknown fixture state %s", state)
				}
			}
			if test.ToolResult != nil {
				if err := plans.Success(ctx, conversation, "findTasks", *test.ToolResult); err != nil {
					t.Fatal(err)
				}
			}
			before, err := plans.Get(ctx, conversation, false)
			if err != nil {
				t.Fatal(err)
			}
			result, changeErr := plans.Change(ctx, conversation, test.Seq, test.Action, test.Note)
			if test.ExpectedError != 0 {
				var business *api.BusinessError
				if !errors.As(changeErr, &business) || int(business.Code()) != test.ExpectedError {
					t.Fatalf("got %v want code %d", changeErr, test.ExpectedError)
				}
			} else {
				if changeErr != nil {
					t.Fatal(changeErr)
				}
				if result.Status != test.ExpectedPlanStatus {
					t.Fatal(result)
				}
				for i, want := range test.ExpectedStates {
					if result.Steps[i].Status != want {
						t.Fatalf("step %d: got %s want %s", i+1, result.Steps[i].Status, want)
					}
				}
				step := result.Steps[test.Seq-1]
				if test.ExpectedReason != nil && (step.Error == nil || *step.Error != *test.ExpectedReason || step.Finished == nil) {
					t.Fatal(step)
				}
				if test.ExpectedToolResult != nil && (step.ToolResult == nil || *step.ToolResult != *test.ExpectedToolResult) {
					t.Fatal(step)
				}
				if test.Action == "start" && (step.Started == nil || step.Finished != nil || step.Error != nil) {
					t.Fatal(step)
				}
				if test.Action == "complete" && (step.Started == nil || step.Finished == nil) {
					t.Fatal(step)
				}
			}
			persisted, err := plans.Get(ctx, conversation, false)
			if err != nil {
				t.Fatal(err)
			}
			normalizePlanTimes(before)
			normalizePlanTimes(result)
			normalizePlanTimes(persisted)
			if test.ExpectedError != 0 || test.Idempotent {
				if !reflect.DeepEqual(before, persisted) {
					t.Fatalf("rejected/idempotent action changed stored plan: before=%+v after=%+v", before, persisted)
				}
			} else if !reflect.DeepEqual(result, persisted) {
				t.Fatalf("returned plan differs from committed plan: returned=%+v stored=%+v", result, persisted)
			}
		})
	}
}

// PostgreSQL 的时间精度为微秒，比较返回值时保留同一持久化精度。
func normalizePlanTimes(p *agent.Plan) {
	if p == nil {
		return
	}
	p.Created = p.Created.UTC().Truncate(time.Microsecond)
	p.Updated = p.Updated.UTC().Truncate(time.Microsecond)
	for i := range p.Steps {
		for _, value := range []**time.Time{&p.Steps[i].Started, &p.Steps[i].Finished} {
			if *value != nil {
				normalized := (**value).UTC().Truncate(time.Microsecond)
				*value = &normalized
			}
		}
	}
}
