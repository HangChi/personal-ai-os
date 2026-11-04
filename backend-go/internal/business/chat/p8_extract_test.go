package chat

import (
	"context"
	"strings"
	"testing"
)

func TestP8PlainExtractionFallbackUnsupportedAndBlank(t *testing.T) {
	a := &Attachments{Directory: t.TempDir(), Maximum: 10 << 20, ExtractPDF: ExtractPDF}
	ctx := context.Background()
	for _, test := range []struct{ name, media, body, want string }{
		{"note.txt", "text/plain", "这是一段说明", "这是一段说明"},
		{"readme.md", "application/octet-stream", "# 标题", "# 标题"},
		{"data.json", "", "{\"a\":1}", "{\"a\":1}"},
	} {
		got, ok := a.extract(ctx, []byte(test.body), test.name, test.media)
		if !ok || got != test.want {
			t.Fatal("text/extension fallback", test.name)
		}
	}
	for _, test := range []struct{ name, media, body string }{
		{"archive.zip", "application/zip", "PK\x03\x04"}, {"photo.png", "image/png", "x"},
		{"empty.txt", "text/plain", " \n\t "}, {"zero.txt", "text/plain", ""}, {"broken.pdf", "application/pdf", "not a pdf"},
	} {
		if _, ok := a.extract(ctx, []byte(test.body), test.name, test.media); ok {
			t.Fatal("unreadable input accepted", test.name)
		}
	}
	file, err := a.Store(ctx, []byte(strings.Repeat("字", 8500)), ptr("large.txt"), ptr("text/plain"))
	if err != nil {
		t.Fatal(err)
	}
	result, err := a.Build(ctx, []Attachment{file})
	if err != nil || !strings.Contains(result.Text, strings.Repeat("字", 8000)) || strings.Contains(result.Text, strings.Repeat("字", 8001)) || !strings.Contains(result.Text, "已截断") {
		t.Fatal("8000 UTF-16 context limit", err)
	}
}
