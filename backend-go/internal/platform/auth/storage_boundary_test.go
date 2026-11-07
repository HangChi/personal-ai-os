package auth

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/platform/api"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
	"strings"
	"testing"
	"time"
)

type boundarySessionStore struct {
	SessionStore
	fail    string
	missing bool
}

func (s boundarySessionStore) Load(context.Context, string) (Session, bool, error) {
	if s.fail == "load" {
		return Session{}, false, errors.New("storage")
	}
	return Session{Username: "admin"}, true, nil
}
func (s boundarySessionStore) EnsureCSRF(context.Context, string, string) (Session, bool, error) {
	if s.fail == "csrf" {
		return Session{}, false, errors.New("storage")
	}
	return Session{}, !s.missing, nil
}
func (s boundarySessionStore) Rotate(context.Context, string, string, Session) error {
	if s.fail == "rotate" {
		return errors.New("storage")
	}
	return nil
}
func (s boundarySessionStore) Delete(context.Context, string) error { return errors.New("storage") }

type failingAuthWriter struct{ header http.Header }

func (w *failingAuthWriter) Header() http.Header     { return w.header }
func (*failingAuthWriter) WriteHeader(int)           {}
func (*failingAuthWriter) Write([]byte) (int, error) { return 0, io.ErrClosedPipe }
func TestAuthStorageHTTPFailuresAreRedacted(t *testing.T) {
	for _, tc := range []struct {
		fail, method, path string
		csrf               bool
	}{
		{"load", "GET", "/api/v1/auth/me", false}, {"csrf", "GET", "/api/v1/auth/csrf", true}, {"rotate", "GET", "/api/v1/auth/csrf", true}, {"delete", "POST", "/api/v1/auth/logout", false}, {"csrf", "POST", "/api/v1/tasks", true},
	} {
		t.Run(tc.fail+tc.path, func(t *testing.T) {
			store := boundarySessionStore{fail: tc.fail, missing: tc.fail == "rotate"}
			handler := newTestHTTP(t, true, tc.csrf, false, store)
			w := requestAuth(handler, tc.method, tc.path, "{}", &http.Cookie{Name: "JSESSIONID", Value: sessionID(1)}, "")
			if w.Code != 500 || strings.Contains(w.Body.String(), "storage") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	service, e := newService(enabledOptions(), &testCodec{}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	store, _ := NewMemoryStore(time.Minute)
	for _, o := range []HTTPOptions{{CookieName: "bad"}, {Cookie: &http.Cookie{Name: "x"}}, {Cookie: &http.Cookie{Name: "x", Path: "/", Value: "bad"}}} {
		if _, e := NewHTTP(service, store, o, logger); e == nil {
			t.Fatal(o)
		}
	}
	if _, e := NewHTTP(nil, store, HTTPOptions{}, logger); e == nil {
		t.Fatal("nil service")
	}
	layer, _ := NewHTTP(service, store, HTTPOptions{}, logger)
	func() {
		defer func() {
			if recover() == nil {
				t.Error("nil downstream accepted")
			}
		}()
		layer.Handler(nil)
	}()
	writer := &failingAuthWriter{http.Header{}}
	layer.success(writer, httptest.NewRequest("GET", "/", nil), nil)
	layer.controllerError(writer, api.InternalError, "")
	layer.writeError(writer, api.InternalError, "")
	r := httptest.NewRequest("POST", "/", strings.NewReader("x=%XX"))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if _, e := csrfInput(httptest.NewRecorder(), r); e == nil {
		t.Fatal("malformed form")
	}
	r = httptest.NewRequest("GET", "/", nil)
	r.URL.Path = "/bad" + string(rune(1))
	if !unsafePath(r) {
		t.Fatal("control path")
	}
}
func TestSessionCancellationInvalidIDsExpiryAndRedaction(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	store, _ := NewMemoryStore(time.Minute)
	if _, _, e := store.Load(ctx, sessionID(1)); e == nil {
		t.Fatal("canceled load")
	}
	if e := store.Rotate(ctx, "", sessionID(1), Session{}); e == nil {
		t.Fatal("canceled rotate")
	}
	if _, _, e := store.EnsureCSRF(ctx, sessionID(1), "token"); e == nil {
		t.Fatal("canceled csrf")
	}
	if e := store.Delete(ctx, sessionID(1)); e == nil {
		t.Fatal("canceled delete")
	}
	if _, _, e := store.Load(context.Background(), "bad"); e == nil {
		t.Fatal("invalid load")
	}
	if _, _, e := store.EnsureCSRF(context.Background(), "bad", ""); e == nil {
		t.Fatal("invalid csrf")
	}
	if e := store.Delete(context.Background(), "bad"); e == nil {
		t.Fatal("invalid delete")
	}
	now := time.Now()
	store.now = func() time.Time { return now }
	store.nextSweep = now.Add(time.Hour)
	store.entries[sessionID(1)] = sessionEntry{Session{Username: "private"}, now.Add(-time.Second)}
	if _, ok, e := store.Load(context.Background(), sessionID(1)); ok || e != nil {
		t.Fatal(ok, e)
	}
	if strings.Contains(fmt.Sprintf("%v %#v %v %#v", store, store, Session{Username: "private"}, Session{Username: "private"}), "private") {
		t.Fatal("leaked identity")
	}
	client, e := redisclient.New(redisclient.Config{URI: "redis://127.0.0.1:1"})
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	rs, e := NewRedisStore(client, "boundary", time.Minute)
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e := rs.Load(ctx, "bad"); e == nil {
		t.Fatal("invalid redis load")
	}
	if e := rs.Rotate(ctx, "", "bad", Session{}); e == nil {
		t.Fatal("invalid redis rotate")
	}
	if e := rs.Rotate(ctx, "", sessionID(1), Session{}); e == nil {
		t.Fatal("canceled redis rotate")
	}
	if _, _, e := rs.EnsureCSRF(ctx, "bad", ""); e == nil {
		t.Fatal("invalid redis csrf")
	}
	if e := rs.Delete(ctx, "bad"); e == nil {
		t.Fatal("invalid redis delete")
	}
	if strings.Contains(fmt.Sprintf("%v %#v", rs, rs), "boundary") {
		t.Fatal("leaked namespace")
	}
	for _, v := range []any{1, "bad", "{}", string([]byte{255})} {
		if _, _, e := decodeSession(v); e == nil {
			t.Fatal(v)
		}
	}
	invalid := DefaultOptions()
	invalid.Username = string([]byte{255})
	if _, e := New(invalid); e == nil {
		t.Fatal("invalid utf8")
	}
}
