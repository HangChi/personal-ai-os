//go:build integration

package chat

import (
	"context"
	"encoding/json"
	"net/http"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"strings"
	"testing"
)

func TestPostgresVerifiedPartialEvidenceUsesProtectedStreamAndPersistsAnswer(t *testing.T) {
	modelCalls := 0
	f := newExecutionFixture(t, func(w http.ResponseWriter, _ *http.Request) { modelCalls++; emitText(w, "unexpected normal model") })
	ctx := context.Background()
	id := fixtureSession(t, f.module.Service, "partial-evidence").ConversationID
	answer := "可确认的资料内容 [1]。尚有适用范围分歧 [2]。"
	title, filename := "合成资料", "evidence.md"
	citations := []Citation{
		{Index: 1, DocumentID: agent.UUID(), DocumentTitle: &title, Filename: &filename, Content: "可确认的资料内容"},
		{Index: 2, DocumentID: agent.UUID(), DocumentTitle: &title, Filename: &filename, Content: "另一适用范围的资料"},
	}
	f.module.Engine.RetrieveConversationEvidence = func(context.Context, string, []knowledge.ConversationMessage, int) (RetrievedEvidence, error) {
		r := knowledge.RagResult{Status: "CONFLICT", Hits: []knowledge.Hit{}, Queries: []string{}, Warnings: []string{}, Stages: []knowledge.Stage{}, Disagreements: []knowledge.Disagreement{}, PreparedAnswer: answer}
		return RetrievedEvidence{Citations: citations, Retrieval: r, Emit: true, Guarded: true, Answer: r.GuardedAnswer()}, nil
	}
	enabled := true
	p, err := f.module.Engine.Prepare(ctx, id, StreamRequest{Content: "分别解释已确认与有分歧的内容", UseKnowledge: &enabled})
	if err != nil {
		t.Fatal(err)
	}
	done, rag := "", ""
	err = f.module.Engine.Run(ctx, p, agent.UUID(), "", nil, func(name string, value any) error {
		raw, _ := json.Marshal(value)
		if name == "done" {
			done = string(raw)
		}
		if name == "rag" {
			rag = string(raw)
		}
		return nil
	})
	if err != nil || modelCalls != 0 || !strings.Contains(done, answer) || strings.Contains(rag, "preparedAnswer") {
		t.Fatal(err, modelCalls, done, rag)
	}
	messages, err := f.module.Service.Messages(ctx, id, nil, nil)
	if err != nil || len(messages) != 2 || messages[1].Content != answer {
		t.Fatal(messages, err)
	}
	if len(messages[1].Citations) != len(citations) {
		t.Fatal("partial answer lost citations", messages[1].Citations)
	}
	for i, citation := range messages[1].Citations {
		if citation.Index != citations[i].Index || citation.DocumentID != citations[i].DocumentID || citation.Content != citations[i].Content {
			t.Fatal("partial answer changed source numbering or snapshot", citation)
		}
	}
}
