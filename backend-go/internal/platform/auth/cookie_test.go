package auth

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestConfiguredCookieRotationAndRemoval(t *testing.T) {
	store, _ := NewMemoryStore(time.Minute)
	service, err := New(DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	options := HTTPOptions{CSRFEnabled: true, CookieSecure: true, Cookie: &http.Cookie{Name: "CUSTOM_SESSION", Path: "/api", Domain: "example.test", HttpOnly: true, SameSite: http.SameSiteStrictMode}}
	layer, err := NewHTTP(service, store, options, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	options.Cookie.Name = "mutated"
	handler := layer.Handler(http.NotFoundHandler())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/auth/csrf", nil))
	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatal("未写入cookie")
	}
	cookie := cookies[0]
	if cookie.Name != "CUSTOM_SESSION" || cookie.Path != "/api" || cookie.Domain != "example.test" || !cookie.Secure || !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatal("Cookie配置丢失")
	}
	r := httptest.NewRequest("POST", "/api/v1/auth/logout", nil)
	r.AddCookie(cookie)
	state, _, err := store.Load(r.Context(), cookie.Value)
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("X-CSRF-TOKEN", state.CSRFToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	removed := w.Result().Cookies()
	if w.Code != 200 || len(removed) != 1 || removed[0].Name != cookie.Name || removed[0].Path != cookie.Path || removed[0].Domain != cookie.Domain || removed[0].MaxAge != -1 {
		t.Fatal("注销未删除同路径Cookie")
	}
}
