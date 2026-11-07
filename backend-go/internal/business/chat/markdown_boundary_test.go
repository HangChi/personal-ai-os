package chat

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestMarkdownExportPreservesRemoteAndUntitledCitationSources(t *testing.T) {
	document := RenderMarkdown(Session{ConversationID: "fixture", Title: "Fixture", CreatedAt: time.Unix(0, 0)}, []Message{{Role: "ASSISTANT", Content: "answer", Attachments: []Attachment{{}}, Citations: []Citation{{Index: 1, URL: "https://example.test/source"}, {Index: 2, ChunkIndex: 0}}}}, time.UTC)
	for _, want := range []string{"**附件**：null", "1. 《未命名》 <https://example.test/source>", "2. 《未命名》（未命名，第 1 段）"} {
		if !strings.Contains(document.Markdown, want) {
			t.Fatal(document.Markdown)
		}
	}
	service, _ := NewService(oracleStore{&engineContractTx{}}, nil)
	if _, err := service.ExportMarkdown(context.Background(), "", time.UTC); err == nil {
		t.Fatal("empty id accepted")
	}
	if _, err := service.ExportMarkdown(context.Background(), "fixture", nil); err == nil {
		t.Fatal("implicit timezone accepted")
	}
}
