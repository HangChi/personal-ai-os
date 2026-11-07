//go:build integration

package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"reflect"
	"testing"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/platform/api"
)

func interactionContractRun(t *testing.T, f *executionFixture, status string) (string, string) {
	t.Helper()
	c := fixtureSession(t, f.module.Service, "交互边界契约").ConversationID
	run := agent.UUID()
	raw, err := json.Marshal(StreamRequest{Content: "等待用户决定"})
	if err != nil {
		t.Fatal(err)
	}
	execute(t, f.pool, "INSERT INTO chat_run(id,conversation_id,request,status) VALUES($1,$2,$3,$4)", run, c, string(raw), status)
	return c, run
}

func expectInteractionConflict(t *testing.T, err error) {
	t.Helper()
	var business *api.BusinessError
	if !errors.As(err, &business) || int(business.Code()) != 40900 {
		t.Fatalf("expected conflict, got %v", err)
	}
}

func TestPostgresQuestionSharedJavaGoBoundaries(t *testing.T) {
	var fixture struct {
		Cases []struct {
			Name, Question, RunStatus string
			Options                   []QuestionOption
			ExpectedError             int
		}
	}
	raw, err := os.ReadFile("../../../../testdata/agent-rag/questions.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	f := newExecutionFixture(t, func(http.ResponseWriter, *http.Request) { t.Error("interaction validation called a model") })
	ctx := context.Background()
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			c, run := interactionContractRun(t, f, test.RunStatus)
			q, err := f.module.Runs.Interactions.Ask(ctx, c, run, test.Question, test.Options)
			if test.ExpectedError != 0 {
				expectInteractionConflict(t, err)
			} else {
				if err != nil {
					t.Fatal(err)
				}
				expected := test.Options
				if expected == nil {
					expected = []QuestionOption{}
				}
				if q.Question != strip(test.Question) || q.Status != "PENDING" || !reflect.DeepEqual(q.Options, expected) {
					t.Fatal(q)
				}
				repeated, err := f.module.Runs.Interactions.Ask(ctx, c, run, test.Question, test.Options)
				if err != nil || !reflect.DeepEqual(q, repeated) {
					t.Fatal(repeated, err)
				}
				if err := f.module.Runs.Interactions.Checkpoint(ctx, run); !errors.Is(err, agent.ErrYield) {
					t.Fatal(err)
				}
			}
			expected := 0
			if test.ExpectedError == 0 {
				expected = 1
			}
			if count := countFor(t, f.pool, "SELECT count(*) FROM agent_question WHERE run_id=$1", run); count != expected {
				t.Fatal(count, expected)
			}
		})
	}
}

func TestPostgresInteractionIdempotencyAndQueueLimit(t *testing.T) {
	f := newExecutionFixture(t, func(http.ResponseWriter, *http.Request) { t.Error("interaction guards called a model") })
	ctx, s := context.Background(), f.module.Runs
	c, run := interactionContractRun(t, f, "RUNNING")
	q, err := s.Interactions.Ask(ctx, c, run, "选择格式？", nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.Interactions.Ask(ctx, c, run, "选择其他格式？", nil)
	expectInteractionConflict(t, err)
	_, err = s.Interactions.Ask(ctx, c, "missing", "问题", nil)
	expectInteractionConflict(t, err)
	execute(t, f.pool, "UPDATE chat_run SET status='STOPPED' WHERE id=$1", run)
	if _, err = s.Answer(ctx, c, run, q.ID, "  表格  "); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Answer(ctx, c, run, q.ID, "表格"); err != nil {
		t.Fatal(err)
	}
	_, err = s.Answer(ctx, c, run, q.ID, "列表")
	expectInteractionConflict(t, err)
	_, err = s.Answer(ctx, c, run, "missing", "表格")
	expectInteractionConflict(t, err)
	execute(t, f.pool, "UPDATE chat_run SET status='RUNNING' WHERE id=$1", run)
	repeated, err := s.Interactions.Ask(ctx, c, run, "选择格式？", nil)
	if err != nil || repeated.ID != q.ID || repeated.Status != "ANSWERED" || value(repeated.Answer) != "表格" {
		t.Fatal(repeated, err)
	}
	if err := s.Interactions.Checkpoint(ctx, run); err != nil {
		t.Fatal(err)
	}
	request := StreamRequest{Content: "保留风险说明"}
	for i := 0; i < 20; i++ {
		if _, err := s.Followup(ctx, c, run, fmt.Sprintf("queued-%d", i), "QUEUE", request); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Followup(ctx, c, run, "queued-0", "QUEUE", request); err != nil {
		t.Fatal(err)
	}
	_, err = s.Followup(ctx, c, run, "overflow", "QUEUE", request)
	expectInteractionConflict(t, err)
	_, err = s.Followup(ctx, c, run, "queued-0", "STEER", request)
	expectInteractionConflict(t, err)
	_, err = s.Followup(ctx, c, run, "queued-0", "QUEUE", StreamRequest{Content: "不同要求"})
	expectInteractionConflict(t, err)
	execute(t, f.pool, "UPDATE chat_run SET status='STOPPED' WHERE id=$1", run)
	other := agent.UUID()
	execute(t, f.pool, "INSERT INTO chat_run(id,conversation_id,request,status) SELECT $1,conversation_id,request,'STOPPED' FROM chat_run WHERE id=$2", other, run)
	_, err = s.Followup(ctx, c, other, "queued-0", "QUEUE", request)
	expectInteractionConflict(t, err)
	if _, err := s.ChangeFollowup(ctx, c, run, "queued-0", "cancel", ""); err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"edit", "steer", "cancel"} {
		_, err = s.ChangeFollowup(ctx, c, run, "queued-0", action, "新要求")
		expectInteractionConflict(t, err)
	}
	result, err := s.Followup(ctx, c, run, "replacement", "QUEUE", request)
	if err != nil || len(result.Followups) != 20 {
		t.Fatal(result, err)
	}
	if count := countFor(t, f.pool, "SELECT count(*) FROM agent_followup WHERE conversation_id=$1", c); count != 21 {
		t.Fatal(count)
	}
}
