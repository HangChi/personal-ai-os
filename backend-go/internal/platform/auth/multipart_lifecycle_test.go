package auth

import (
	"bytes"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestMultipartCSRFAndBusinessShareLifecycle(t *testing.T) {
	for _, mode := range []string{"body", "header", "bad-token", "oversize", "panic"} {
		t.Run(mode, func(t *testing.T) {
			dir := t.TempDir()
			store, _ := NewMemoryStore(time.Minute)
			service, err := New(Options{Username: "admin", MaxFailures: 5, Lockout: time.Minute})
			if err != nil {
				t.Fatal(err)
			}
			logger := slog.New(slog.NewTextHandler(io.Discard, nil))
			layer, err := NewHTTP(service, store, HTTPOptions{CSRFEnabled: true, Multipart: httpmiddleware.MultipartLimits{MaxFileBytes: 64, MaxRequestBytes: 1024, TempDirectory: dir}}, logger)
			if err != nil {
				t.Fatal(err)
			}
			called := false
			var saved *httpmiddleware.UploadFile
			handler := layer.Handler(httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
				called = true
				upload, err := httpmiddleware.RequestMultipart(w, r)
				if err != nil {
					return err
				}
				second, err := httpmiddleware.RequestMultipart(w, r)
				if err != nil || second != upload {
					t.Fatal("重复解析请求")
				}
				saved = upload.Files["file"][0]
				file, err := saved.Open()
				if err != nil {
					t.Fatal(err)
				}
				data, err := io.ReadAll(file)
				file.Close()
				if err != nil || string(data) != "fixture" || saved.Filename != "中文.txt" {
					t.Fatal("CSRF吞掉上传正文")
				}
				if mode == "panic" {
					panic("private")
				}
				w.WriteHeader(204)
				return nil
			}, logger))
			handler = httpmiddleware.RequestIDMiddleware(handler)
			first := requestAuth(handler, "GET", "/api/v1/auth/csrf", "", nil, "")
			token, cookie := csrfData(t, first)
			var body bytes.Buffer
			writer := multipart.NewWriter(&body)
			presented := token
			if mode == "bad-token" {
				presented = "bad"
			}
			writer.WriteField("_csrf", presented)
			part, _ := writer.CreateFormFile("file", "中文.txt")
			if mode == "oversize" {
				part.Write(bytes.Repeat([]byte("a"), 65))
			} else {
				part.Write([]byte("fixture"))
			}
			writer.Close()
			r := httptest.NewRequest("POST", "/upload", &body)
			r.Header.Set("Content-Type", writer.FormDataContentType())
			r.AddCookie(cookie)
			if mode == "header" || mode == "oversize" {
				r.Header.Set("X-CSRF-TOKEN", token)
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			want := 204
			if mode == "bad-token" {
				want = 403
			}
			if mode == "oversize" {
				want = 400
			}
			if mode == "panic" {
				want = 500
			}
			if w.Code != want || called != (mode == "body" || mode == "header" || mode == "panic") {
				t.Fatalf("生命周期顺序错误: %d %s", w.Code, w.Body.String())
			}
			entries, err := os.ReadDir(dir)
			if err != nil || len(entries) != 0 {
				t.Fatal("请求结束暂存未清理", err)
			}
			if saved != nil {
				if f, err := saved.Open(); err == nil {
					f.Close()
					t.Fatal("文件跨请求存活")
				}
			}
		})
	}
}
