//go:build integration

package app

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/chat"
)

// 只验证显式组合端口；P4 未完成前不在生产 Foundation/CLI 中开启会话路由。
func TestFoundationP4ChatStorageSecurityComposition(t *testing.T) {
	var business http.Handler
	f := fixtureFoundation(t, map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true", "app.rate-limit.enabled": "false", "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none"}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { business.ServeHTTP(w, r) }))
	store, err := chat.NewPostgresStore(f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	svc, err := chat.NewService(store, nil)
	if err != nil {
		t.Fatal(err)
	}
	business, err = chat.NewHTTP(svc, time.UTC, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	client := server.Client()
	base := server.URL + "/api/v1/chat/sessions"
	if code, _ := appRequest(t, client, "GET", base, "", ""); code != 401 {
		t.Fatal("chat authentication bypass", code)
	}
	if code, _ := appRequest(t, client, "POST", base, "", `{"title":"must not create"}`); code != 403 {
		t.Fatal("chat CSRF bypass", code)
	}
	jar, _ := cookiejar.New(nil)
	client.Jar = jar
	code, body := appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	if code != 200 {
		t.Fatal(code, body)
	}
	token := body["data"].(map[string]any)["token"].(string)
	if code, body = appRequest(t, client, "POST", server.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`); code != 200 {
		t.Fatal(code, body)
	}
	_, body = appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	token = body["data"].(map[string]any)["token"].(string)
	code, body = appRequest(t, client, "POST", base, token, `{"title":"P4 protected fixture"}`)
	if code != 201 {
		t.Fatal(code, body)
	}
	id := body["data"].(map[string]any)["conversationId"].(string)
	defer func() {
		if err := svc.Delete(context.Background(), id); err != nil {
			t.Error(err)
		}
	}()
	code, body = appRequest(t, client, "GET", base+"/"+id+"/messages", "", "")
	if code != 200 || len(body["data"].([]any)) != 0 {
		t.Fatal("protected storage unavailable", code, body)
	}
	response, err := client.Get(server.URL + "/readyz")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 503 {
		t.Fatal("P4 storage bypassed migration readiness gate")
	}
}

func TestFoundationP4WorkerRejectsChatStorage(t *testing.T) {
	for _, role := range []string{"index", "review", "delivery"} {
		t.Run(role, func(t *testing.T) {
			called := false
			f := fixtureFoundation(t, map[string]string{"app.runtime.role": role, "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none"}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true }))
			response := httptest.NewRecorder()
			f.Handler().ServeHTTP(response, httptest.NewRequest("GET", "/api/v1/chat/sessions", nil))
			if called || response.Code != 404 || response.Body.Len() != 0 {
				t.Fatal("worker exposed chat storage", called, response.Code, response.Body.String())
			}
		})
	}
}
