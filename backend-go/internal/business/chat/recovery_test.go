package chat

import (
	"context"
	"errors"
	"io"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"strings"
	"testing"
)

func TestAutomaticClosingEligibilityAndDeterministicOutput(t *testing.T) {
	for _, err := range []error{context.DeadlineExceeded, &model.UpstreamError{Status: 503}, io.ErrUnexpectedEOF,
		api.NewBusinessError(api.AgentExecutionLimit, "limit")} {
		if !recoveryEligible(err) {
			t.Fatal("recoverable failure rejected", err)
		}
	}
	for _, err := range []error{context.Canceled, agent.ErrYield, errors.New("internal"), api.NewBusinessError(api.ValidationFailed, "input")} {
		if recoveryEligible(err) {
			t.Fatal("unsafe automatic recovery", err)
		}
	}
	if !strings.Contains(recoveryText(""), "没有可核实") {
		t.Fatal(recoveryText(""))
	}
	if !strings.Contains(recoveryText("external [IN_PROGRESS]: 待核实"), "external [IN_PROGRESS]") {
		t.Fatal("lost unknown receipt")
	}
}
