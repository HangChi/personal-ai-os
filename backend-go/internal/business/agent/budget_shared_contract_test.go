package agent

import (
	"encoding/json"
	"errors"
	"os"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

func TestSharedExecutionBudgetSafetyBoundaries(t *testing.T) {
	var fixture struct {
		Cases []struct {
			Name             string
			Models, Failures int
			Steps            []struct {
				Op, Name, Digest, Status string
				Allowed, Write, Expected bool
			}
		}
	}
	raw, err := os.ReadFile("../../../../testdata/agent-rag/execution-budget.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) == 0 {
		t.Fatal("empty shared contract")
	}
	for _, scenario := range fixture.Cases {
		t.Run(scenario.Name, func(t *testing.T) {
			budget := NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: scenario.Models, MaxConsecutiveFailures: scenario.Failures})
			for i, step := range scenario.Steps {
				switch step.Op {
				case "model", "tool":
					var err error
					if step.Op == "model" {
						err = budget.BeforeModel()
					} else {
						err = budget.BeforeTool(step.Name, step.Digest, 0, 0)
					}
					if (err == nil) != step.Allowed {
						t.Fatalf("step %d: allowed=%v error=%v", i, step.Allowed, err)
					}
					if !step.Allowed {
						var business *api.BusinessError
						if !errors.As(err, &business) || business.Code() != api.AgentExecutionLimit {
							t.Fatalf("step %d: expected execution-limit code, got %v", i, err)
						}
					}
				case "record":
					budget.Record(step.Name, step.Digest, Result{Status: step.Status}, step.Write)
				case "has_write":
					if got := budget.HasWrite(step.Name); got != step.Expected {
						t.Fatalf("step %d: write evidence=%v want=%v", i, got, step.Expected)
					}
				default:
					t.Fatalf("unknown contract operation %q", step.Op)
				}
			}
		})
	}
}
