package knowledge

import (
	"bytes"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"

	web "personal-ai-os/backend-go/internal/platform/http"
)

func TestP8UploadRequestParameters(t *testing.T) {
	text := func(value string) *string { return &value }
	const id = "00000000-0000-4000-8000-000000000001"
	for _, tt := range []struct {
		name, query string
		fields      [][2]string
		title       *string
		target      string
		invalid     bool
	}{
		{name: "missing"},
		{name: "query", query: "title=query+title", title: text("query title")},
		{name: "query-repeat", query: "title=first&title=second", title: text("first,second")},
		{name: "form", fields: [][2]string{{"title", "form title"}}, title: text("form title")},
		{name: "form-repeat", fields: [][2]string{{"title", "first"}, {"title", "second"}}, title: text("first,second")},
		{name: "form-before-query", query: "title=query", fields: [][2]string{{"title", "form"}}, title: text("form,query")},
		{name: "empty-query-kept", query: "title=", fields: [][2]string{{"title", "form"}}, title: text("form,")},
		{name: "unicode", query: "title=%E4%B8%AD%E6%96%87+%E6%A0%87%E9%A2%98%2B%25", title: text("中文 标题+%")},
		{name: "empty-form", fields: [][2]string{{"title", ""}}, title: text("")},
		{name: "literal-comma", query: "title=first%2Csecond", fields: [][2]string{{"title", "third"}}, title: text("third,first,second")},
		{name: "query-target", query: "documentId=" + id, target: id},
		{name: "form-target-first", query: "documentId=bad", fields: [][2]string{{"documentId", id}}, target: id},
		{name: "invalid-target", query: "documentId=bad", invalid: true},
		{name: "empty-target", query: "documentId="},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var body bytes.Buffer
			writer := multipart.NewWriter(&body)
			for _, field := range tt.fields {
				if err := writer.WriteField(field[0], field[1]); err != nil {
					t.Fatal(err)
				}
			}
			file, err := writer.CreateFormFile("file", "binding.md")
			if err != nil {
				t.Fatal(err)
			}
			if _, err = io.WriteString(file, "original payload"); err != nil {
				t.Fatal(err)
			}
			if err = writer.Close(); err != nil {
				t.Fatal(err)
			}
			request := httptest.NewRequest("POST", "/api/v1/knowledge/documents?"+tt.query, &body)
			request.Header.Set("Content-Type", writer.FormDataContentType())
			called := false
			handler := web.MultipartLifecycle(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				called = true
				filename, title, target, payload, err := readUpload(w, r)
				if tt.invalid {
					if err == nil {
						t.Fatal("invalid documentId accepted")
					}
					return
				}
				if err != nil || filename != "binding.md" || target != tt.target || string(payload) != "original payload" {
					t.Fatalf("filename=%q target=%q payload=%q err=%v", filename, target, payload, err)
				}
				if (title == nil) != (tt.title == nil) || title != nil && *title != *tt.title {
					t.Fatalf("title=%v want=%v", title, tt.title)
				}
			}), web.MultipartLimits{MaxFileBytes: 1 << 20, MaxRequestBytes: 2 << 20, TempDirectory: t.TempDir()})
			handler.ServeHTTP(httptest.NewRecorder(), request)
			if !called {
				t.Fatal("upload handler did not run")
			}
		})
	}
}
