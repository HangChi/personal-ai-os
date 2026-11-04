//go:build integration

package app

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"

	"personal-ai-os/backend-go/internal/business/chat"
)

func TestP8ConflictingEvidenceBypassesAnswerAndPersistsBothSources(t *testing.T) {
	var calls, retrievals atomic.Int32
	f := p6Foundation(t, "api", p6Upstream(t, &calls), nil, nil)
	ctx := context.Background()
	citations := []chat.Citation{}
	for i, content := range []string{"标准为每人100元。", "标准为每人200元。"} {
		doc, err := f.p6.Store.Ingest(ctx, "conflict.md", content, []byte(strings.Repeat(content, 60)))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _, _ = f.p6.Store.Delete(context.Background(), doc.ID) })
		title, filename := content, "conflict.md"
		citations = append(citations, chat.Citation{Index: i + 1, DocumentID: doc.ID, DocumentTitle: &title, Filename: &filename, ChunkIndex: 0, Content: content})
	}
	answer := "资料存在不同说法[1][2]，请核对适用日期后再确定标准。"
	// The original Java contract stubs the retrieval boundary too; generation and persistence stay real.
	f.Chat().Engine.RetrieveEvidence = func(_ context.Context, query string, _ int) (chat.RetrievedEvidence, error) {
		retrievals.Add(1)
		if query != "标准是多少" {
			t.Error("retrieval query changed")
		}
		return chat.RetrievedEvidence{Citations: citations, Retrieval: map[string]any{"status": "CONFLICTED", "citations": citations}, Emit: true, Guarded: true, Answer: answer}, nil
	}
	title := "P8 conflicting evidence"
	session, err := f.Chat().Service.Create(ctx, &title)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = f.Chat().Service.Delete(context.Background(), session.ConversationID) })
	server := httptest.NewServer(f.Handler())
	t.Cleanup(server.Close)
	response, err := server.Client().Post(server.URL+"/api/v1/chat/sessions/"+session.ConversationID+"/stream", "application/json", strings.NewReader(`{"content":"标准是多少","useKnowledge":true}`))
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != http.StatusOK {
		t.Fatal("stream failed", response.StatusCode, err)
	}
	stream := string(body)
	for _, event := range []string{"event: rag", "event: citations", "event: delta", "event: done", "不同说法", "适用日期"} {
		if !strings.Contains(stream, event) {
			t.Fatal("missing guarded SSE contract", event, stream)
		}
	}
	if strings.Contains(stream, "event: error") || calls.Load() != 0 || retrievals.Load() != 1 {
		t.Fatal("guarded response called answer model or retried retrieval", calls.Load(), retrievals.Load())
	}
	messages, err := f.Chat().Service.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(messages) != 2 {
		t.Fatal("message persistence", len(messages), err)
	}
	if messages[0].Role != "USER" || messages[0].Content != "标准是多少" || messages[1].Role != "ASSISTANT" || messages[1].Content != answer || !reflect.DeepEqual(messages[1].Citations, citations) {
		t.Fatal("both ordered source snapshots must persist", messages)
	}
}
