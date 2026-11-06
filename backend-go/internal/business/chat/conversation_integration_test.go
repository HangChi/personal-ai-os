//go:build integration

package chat

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/model"
	"strings"
	"testing"
)

func TestPostgresConversationRetrievalPreservesActionHistoryAndFallback(t *testing.T) {
	for _, failed := range []bool{false, true} {
		t.Run(map[bool]string{false: "action", true: "route-failure"}[failed], func(t *testing.T) {
			modelCalls := 0
			f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
				var input struct{ Messages []model.Message }
				if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
					t.Error(err)
				}
				raw, _ := json.Marshal(input.Messages)
				for _, text := range []string{"双选会", "08:00", "八点半"} {
					if !strings.Contains(string(raw), text) {
						t.Errorf("normal model lost history: %s", text)
					}
				}
				modelCalls++
				emitText(w, "收到八点半的修改要求")
			})
			ctx := context.Background()
			c := fixtureSession(t, f.module.Service, "knowledge-action").ConversationID
			if err := f.module.Service.Append(ctx, c, []NewMessage{{Role: "USER", Content: "明天早上8点我要参加双选会"}, {Role: "ASSISTANT", Content: "已创建双选会：2026-11-07 08:00–09:00"}}); err != nil {
				t.Fatal(err)
			}
			called := false
			f.module.Engine.RetrieveConversationEvidence = func(_ context.Context, q string, history []knowledge.ConversationMessage, k int) (RetrievedEvidence, error) {
				called = true
				if q != "改一下时间，改成八点半" || len(history) != 2 || !strings.Contains(history[1].Content, "08:00") {
					t.Fatal(q, history)
				}
				if failed {
					return RetrievedEvidence{}, errors.New("synthetic routing timeout")
				}
				return RetrievedEvidence{}, nil
			}
			enabled := true
			p, err := f.module.Engine.Prepare(ctx, c, StreamRequest{Content: "改一下时间，改成八点半", UseKnowledge: &enabled})
			if err != nil {
				t.Fatal(err)
			}
			events := []string{}
			err = f.module.Engine.Run(ctx, p, agent.UUID(), "", nil, func(name string, _ any) error { events = append(events, name); return nil })
			if err != nil || !called || modelCalls == 0 {
				t.Fatal(err, called, modelCalls)
			}
			if strings.Contains(strings.Join(events, ","), "rag") {
				t.Fatal("action was blocked by document guard", events)
			}
			if failed && !strings.Contains(strings.Join(events, ","), "warning") {
				t.Fatal("missing fallback warning", events)
			}
			messages, err := f.module.Service.Messages(ctx, c, nil, nil)
			if err != nil || len(messages) != 4 || messages[3].Content != "收到八点半的修改要求" {
				t.Fatal(messages, err)
			}
		})
	}
}
