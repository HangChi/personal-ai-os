package chat

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
)

// 非法输入在开启事务或调用模型之前拒绝；合法边界由 PostgreSQL 契约测试验证。
func TestQuestionSharedContractRejectsInvalidInputBeforeDatabase(t *testing.T) {
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
	for _, test := range fixture.Cases {
		if test.ExpectedError == 0 || test.RunStatus != "RUNNING" {
			continue
		}
		t.Run(test.Name, func(t *testing.T) {
			_, err := (&Interactions{}).Ask(context.Background(), "c", "r", test.Question, test.Options)
			var business *api.BusinessError
			if !errors.As(err, &business) || int(business.Code()) != test.ExpectedError {
				t.Fatal(err)
			}
		})
	}
}

func TestInteractionRejectsInvalidAnswersAndFollowupsBeforeDatabase(t *testing.T) {
	s, ctx := &Runs{}, context.Background()
	for _, answer := range []string{"", "  ", strings.Repeat("a", 4001), strings.Repeat("😀", 2001)} {
		if _, err := s.Answer(ctx, "c", "r", "q", answer); err == nil {
			t.Fatal("invalid answer accepted")
		}
	}
	for _, mode := range []string{"", "queue", "UNKNOWN"} {
		if _, err := s.Followup(ctx, "c", "r", "key", mode, StreamRequest{Content: "要求"}); err == nil {
			t.Fatal(mode)
		}
	}
	for _, request := range []StreamRequest{{}, {Content: " "}, {Content: strings.Repeat("a", 4001)}} {
		if _, err := s.Followup(ctx, "c", "r", "key", "QUEUE", request); err == nil {
			t.Fatal(request)
		}
	}
	if _, err := s.Followup(ctx, "c", "r", strings.Repeat("a", 65), "QUEUE", StreamRequest{Content: "要求"}); err == nil {
		t.Fatal("oversized key accepted")
	}
}
