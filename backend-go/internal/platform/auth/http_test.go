package auth

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func newTestHTTP(t *testing.T, enabled, csrf, secure bool, store SessionStore) http.Handler {
	t.Helper()
	options := enabledOptions()
	options.Enabled = enabled
	service, err := newService(options, &testCodec{}, time.Now)
	if err != nil {
		t.Fatal(err)
	}
	layer, err := NewHTTP(service, store, HTTPOptions{CSRFEnabled: csrf, CookieSecure: secure}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	return httpmiddleware.RequestIDMiddleware(layer.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, ok := CurrentUsername(r.Context()); ok {
			w.Header().Set("X-Test-User", user)
		}
		w.WriteHeader(http.StatusNoContent)
	})))
}

func requestAuth(handler http.Handler, method, path, body string, cookie *http.Cookie, token string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Request-Id", "auth-test-123")
	if cookie != nil {
		request.AddCookie(cookie)
	}
	if token != "" {
		request.Header.Set("X-CSRF-TOKEN", token)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func assertAuthStatus(t *testing.T, response *httptest.ResponseRecorder, status int, code api.ErrorCode) map[string]any {
	t.Helper()
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err, response.Body.String())
	}
	if response.Code != status || body["code"] != float64(code) {
		t.Fatalf("status %d body %s", response.Code, response.Body.String())
	}
	if body["timestamp"] == nil || response.Header().Get("X-Request-Id") != "auth-test-123" {
		t.Fatal("envelope/request ID missing")
	}
	return body
}

func csrfData(t *testing.T, response *httptest.ResponseRecorder) (string, *http.Cookie) {
	t.Helper()
	body := assertAuthStatus(t, response, 200, api.Success)
	data := body["data"].(map[string]any)
	if data["headerName"] != "X-CSRF-TOKEN" {
		t.Fatal("header name")
	}
	token, ok := data["token"].(string)
	if !ok || token == "" {
		t.Fatal("missing token")
	}
	var cookie *http.Cookie
	if cookies := response.Result().Cookies(); len(cookies) > 0 {
		cookie = cookies[0]
	}
	return token, cookie
}

const loginJSON = `{"username":"admin","password":"test-secret"}`

func TestHTTPLoginRotationLogoutAndCSRF(t *testing.T) {
	store, _ := NewMemoryStore(DefaultSessionTimeout)
	handler := newTestHTTP(t, true, true, true, store)
	for _, path := range []string{"/api/v1/tasks", "/assets/main.js", "/mcp", "/api/v1/reminders/events"} {
		body := assertAuthStatus(t, requestAuth(handler, "GET", path, "", nil, ""), 401, api.Unauthorized)
		if body["data"] != nil {
			t.Fatal("error must retain null data")
		}
	}
	me := assertAuthStatus(t, requestAuth(handler, "GET", "/api/v1/auth/me", "", nil, ""), 200, api.Success)
	if data := me["data"].(map[string]any); data["username"] != nil || data["authEnabled"] != true {
		t.Fatal("anonymous contract")
	}
	for _, path := range []string{"/api/v1/health", "/actuator/health", "/actuator/health/readiness", "/error"} {
		if r := requestAuth(handler, "GET", path, "", nil, ""); r.Code != 204 || len(r.Result().Cookies()) != 0 {
			t.Fatal("public safe request must not create session", path)
		}
	}
	denied := requestAuth(handler, "POST", "/api/v1/auth/login", loginJSON, nil, "")
	assertAuthStatus(t, denied, 403, api.Forbidden)
	if len(denied.Result().Cookies()) != 1 {
		t.Fatal("CSRF repository must create anonymous session")
	}
	token, anon := csrfData(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", nil, ""))
	if anon == nil || anon.Name != "JSESSIONID" || !anon.HttpOnly || !anon.Secure || anon.SameSite != http.SameSiteLaxMode || anon.Path != "/" {
		t.Fatal("cookie attributes", anon)
	}
	again, _ := csrfData(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", anon, ""))
	if again != token {
		t.Fatal("token changed in same session")
	}
	login := requestAuth(handler, "POST", "/api/v1/auth/login", loginJSON, anon, token)
	assertAuthStatus(t, login, 200, api.Success)
	cookie := login.Result().Cookies()[0]
	if cookie.Value == anon.Value {
		t.Fatal("session fixation: ID not rotated")
	}
	if _, ok, _ := store.Load(context.Background(), anon.Value); ok {
		t.Fatal("old session not invalidated")
	}
	assertAuthStatus(t, requestAuth(handler, "GET", "/api/v1/tasks", "", anon, ""), 401, api.Unauthorized)
	if r := requestAuth(handler, "GET", "/api/v1/tasks", "", cookie, ""); r.Code != 204 || r.Header().Get("X-Test-User") != "admin" {
		t.Fatal("authenticated principal")
	}
	assertAuthStatus(t, requestAuth(handler, "POST", "/api/v1/tasks", "{}", cookie, token), 403, api.Forbidden)
	newToken, _ := csrfData(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", cookie, ""))
	if newToken == token {
		t.Fatal("pre-login token survived rotation")
	}
	if r := requestAuth(handler, "POST", "/api/v1/tasks", "{}", cookie, newToken); r.Code != 204 {
		t.Fatal("valid CSRF blocked")
	}
	if r := requestAuth(handler, "POST", "/mcp/tools", "{}", cookie, ""); r.Code != 204 {
		t.Fatal("MCP CSRF exemption")
	}
	assertAuthStatus(t, requestAuth(handler, "POST", "/mcp/tools", "{}", nil, ""), 401, api.Unauthorized)
	assertAuthStatus(t, requestAuth(handler, "POST", "/api/v1/auth/logout", "", cookie, ""), 403, api.Forbidden)
	logout := requestAuth(handler, "POST", "/api/v1/auth/logout", "", cookie, newToken)
	body := assertAuthStatus(t, logout, 200, api.Success)
	if body["data"] != nil {
		t.Fatal("logout data must be null")
	}
	if c := logout.Result().Cookies()[0]; c.MaxAge != -1 || c.Value != "" {
		t.Fatal("cookie not removed")
	}
	assertAuthStatus(t, requestAuth(handler, "GET", "/api/v1/tasks", "", cookie, ""), 401, api.Unauthorized)
}

func TestHTTPDisabledValidationAndLockout(t *testing.T) {
	store, _ := NewMemoryStore(time.Minute)
	handler := newTestHTTP(t, false, false, false, store)
	for _, row := range []struct{ method, path, body string }{
		{"GET", "/api/v1/auth/me", ""}, {"POST", "/api/v1/auth/login", loginJSON},
	} {
		r := requestAuth(handler, row.method, row.path, row.body, nil, "")
		body := assertAuthStatus(t, r, 200, api.Success)
		data := body["data"].(map[string]any)
		if data["authEnabled"] != false || data["username"] != nil || len(r.Result().Cookies()) != 0 {
			t.Fatal("disabled auth created fake login")
		}
	}
	csrf := assertAuthStatus(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", nil, ""), 200, api.Success)
	if csrf["data"].(map[string]any)["token"] != nil {
		t.Fatal("disabled CSRF should have null token")
	}
	if r := requestAuth(handler, "POST", "/api/v1/tasks", "{}", nil, ""); r.Code != 204 {
		t.Fatal("disabled auth should permit")
	}
	for _, body := range []string{`{}`, `null`, `{"username":null,"password":"x"}`, `{"username":"x","password":" "}`, `{"username":2,"password":[]}`, `{broken`} {
		assertAuthStatus(t, requestAuth(handler, "POST", "/api/v1/auth/login", body, nil, ""), 400, api.ValidationFailed)
	}
	if r := requestAuth(handler, "PATCH", "/api/v1/auth/me", "", nil, ""); r.Code != 405 || r.Header().Get("Allow") != "" {
		t.Fatal("method contract")
	}
	handler = newTestHTTP(t, true, false, false, store)
	for i := 0; i < 5; i++ {
		r := requestAuth(handler, "POST", "/api/v1/auth/login", `{"username":"root","password":"bad"}`, nil, "")
		body := assertAuthStatus(t, r, 401, api.Unauthorized)
		if body["message"] != "用户名或密码错误" {
			t.Fatal("credential enumeration")
		}
	}
	locked := assertAuthStatus(t, requestAuth(handler, "POST", "/api/v1/auth/login", loginJSON, nil, ""), 429, api.RateLimited)
	if !strings.Contains(locked["message"].(string), "过于频繁") {
		t.Fatal("lockout message")
	}
}

type failingSessions struct{ calls atomic.Int32 }

func (store *failingSessions) Load(context.Context, string) (Session, bool, error) {
	store.calls.Add(1)
	return Session{}, false, errors.New("secret backend credentials")
}
func (*failingSessions) Rotate(context.Context, string, string, Session) error {
	return errors.New("secret backend credentials")
}
func (*failingSessions) Delete(context.Context, string) error {
	return errors.New("secret backend credentials")
}
func (*failingSessions) EnsureCSRF(context.Context, string, string) (Session, bool, error) {
	return Session{}, false, errors.New("secret backend credentials")
}

func TestHTTPStorageFailureAndUnsafePathsDeny(t *testing.T) {
	store := &failingSessions{}
	handler := newTestHTTP(t, true, false, false, store)
	for _, path := range []string{"/api/v1/tasks", "/api/v1/auth/me", "/api/v1/health"} {
		r := requestAuth(handler, "GET", path, "", &http.Cookie{Name: "JSESSIONID", Value: sessionID(1)}, "")
		assertAuthStatus(t, r, 500, api.InternalError)
		if strings.Contains(r.Body.String(), "secret") {
			t.Fatal("backend error leaked")
		}
	}
	for _, path := range []string{"/api/v1/auth/me;anything", "/api//v1/auth/me", "/api/v1/auth/me/../tasks", "/api/v1/auth%2fme", "/api/v1/auth/%256de"} {
		assertAuthStatus(t, requestAuth(handler, "GET", path, "", nil, ""), 400, api.ValidationFailed)
	}
	if store.calls.Load() != 3 {
		t.Fatal("unsafe paths reached session backend")
	}
}

func TestAuthActualHTTPCookieJar(t *testing.T) {
	store, _ := NewMemoryStore(time.Minute)
	server := httptest.NewServer(newTestHTTP(t, true, true, false, store))
	defer server.Close()
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar, Timeout: 5 * time.Second}
	send := func(method, path, body, token string) (int, map[string]any) {
		t.Helper()
		r, _ := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("X-CSRF-TOKEN", token)
		}
		response, err := client.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var result map[string]any
		if response.StatusCode != 204 {
			if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
				t.Fatal(err)
			}
		}
		return response.StatusCode, result
	}
	_, before := send("GET", "/api/v1/auth/csrf", "", "")
	token := before["data"].(map[string]any)["token"].(string)
	base, _ := url.Parse(server.URL)
	old := jar.Cookies(base)[0].Value
	if status, _ := send("POST", "/api/v1/auth/login", loginJSON, token); status != 200 {
		t.Fatal("login", status)
	}
	if jar.Cookies(base)[0].Value == old {
		t.Fatal("cookie jar ID not rotated")
	}
	if status, _ := send("GET", "/api/v1/tasks", "", ""); status != 204 {
		t.Fatal("cookie jar authentication", status)
	}
	_, after := send("GET", "/api/v1/auth/csrf", "", "")
	token = after["data"].(map[string]any)["token"].(string)
	if status, _ := send("POST", "/api/v1/auth/logout", "", token); status != 200 {
		t.Fatal("logout", status)
	}
	if len(jar.Cookies(base)) != 0 {
		t.Fatal("logout cookie retained")
	}
	if status, _ := send("GET", "/api/v1/tasks", "", ""); status != 401 {
		t.Fatal("logout access", status)
	}
}

func TestHTTPTokenBindingsFormsAndHead(t *testing.T) {
	store, _ := NewMemoryStore(time.Minute)
	handler := newTestHTTP(t, false, true, false, store)
	firstToken, firstCookie := csrfData(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", nil, ""))
	otherToken, _ := csrfData(t, requestAuth(handler, "GET", "/api/v1/auth/csrf", "", nil, ""))
	assertAuthStatus(t, requestAuth(handler, "POST", "/api/v1/tasks", "{}", firstCookie, otherToken), 403, api.Forbidden)
	if r := requestAuth(handler, "POST", "/api/v1/tasks", "{}", firstCookie, firstToken); r.Code != 204 {
		t.Fatal("CSRF must work independently of authentication")
	}
	for _, formType := range []string{"application/x-www-form-urlencoded", "multipart/form-data"} {
		var body bytes.Buffer
		contentType := formType
		if formType == "multipart/form-data" {
			writer := multipart.NewWriter(&body)
			if err := writer.WriteField("_csrf", firstToken); err != nil {
				t.Fatal(err)
			}
			if err := writer.Close(); err != nil {
				t.Fatal(err)
			}
			contentType = writer.FormDataContentType()
		} else {
			body.WriteString(url.Values{"_csrf": {firstToken}}.Encode())
		}
		r := httptest.NewRequest("POST", "/api/v1/tasks", &body)
		r.Header.Set("Content-Type", contentType)
		r.AddCookie(firstCookie)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, r)
		if response.Code != 204 {
			t.Fatal("form token rejected", formType, response.Body.String())
		}
	}
	if r := requestAuth(handler, "POST", "/api/v1/tasks?_csrf="+firstToken, "{}", firstCookie, ""); r.Code != 204 {
		t.Fatal("parameter token rejected")
	}
	if r := requestAuth(handler, "HEAD", "/api/v1/auth/me", "", nil, ""); r.Code != 200 || r.Body.Len() != 0 {
		t.Fatal("HEAD must not include body")
	}
	request := httptest.NewRequest("GET", "/api/v1/auth/me", nil)
	request.Header.Set("X-Request-Id", "auth-test-123")
	request.AddCookie(&http.Cookie{Name: "JSESSIONID", Value: ""})
	request.AddCookie(firstCookie)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	assertAuthStatus(t, response, 400, api.ValidationFailed)
}

type rotateFailureStore struct{ SessionStore }

func (*rotateFailureStore) Rotate(context.Context, string, string, Session) error {
	return errors.New("secret password in simulated outage")
}

func TestHTTPFailedRotationDoesNotIssueCookieOrLeak(t *testing.T) {
	store, _ := NewMemoryStore(time.Minute)
	oldID := sessionID(7)
	if err := store.Rotate(context.Background(), "", oldID, Session{Username: "admin"}); err != nil {
		t.Fatal(err)
	}
	service, _ := newService(enabledOptions(), &testCodec{}, time.Now)
	var logs bytes.Buffer
	layer, err := NewHTTP(service, &rotateFailureStore{store}, HTTPOptions{}, slog.New(slog.NewTextHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	handler := httpmiddleware.RequestIDMiddleware(layer.Handler(http.NotFoundHandler()))
	r := requestAuth(handler, "POST", "/api/v1/auth/login?secret-query", loginJSON, &http.Cookie{Name: "JSESSIONID", Value: oldID}, "")
	assertAuthStatus(t, r, 500, api.InternalError)
	if len(r.Result().Cookies()) != 0 {
		t.Fatal("failed rotation issued cookie")
	}
	if state, ok, _ := store.Load(context.Background(), oldID); !ok || state.Username != "admin" {
		t.Fatal("failed rotation invalidated old session")
	}
	for _, secret := range []string{"test-secret", "secret-query", "simulated outage", oldID} {
		if strings.Contains(r.Body.String()+logs.String(), secret) {
			t.Fatal("credentials or storage error leaked")
		}
	}
}
