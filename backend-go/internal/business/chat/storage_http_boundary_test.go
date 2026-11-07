package chat

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type brokenRequestBody struct{}

func (brokenRequestBody) Read([]byte) (int, error) { return 0, serviceFailure }
func (brokenRequestBody) Close() error             { return nil }
func TestStorageHTTPValidatesMethodsParametersAndNegotiation(t *testing.T) {
	svc, _ := NewService(oracleStore{&engineContractTx{serviceTx: serviceTx{session: Session{ConversationID: "c"}}}}, nil)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler, e := NewHTTP(svc, time.UTC, logger)
	if e != nil {
		t.Fatal(e)
	}
	for _, tc := range []struct {
		method, path string
		status       int
	}{
		{"GET", "/other", 404}, {"GET", "/api/v1/chat/sessions/", 404}, {"GET", "/api/v1/chat/sessions/c/a/b", 404},
		{"PUT", "/api/v1/chat/sessions", 405}, {"POST", "/api/v1/chat/sessions/search", 405}, {"GET", "/api/v1/chat/sessions/search?page=bad", 400}, {"GET", "/api/v1/chat/sessions/search?size=bad", 400},
		{"GET", "/api/v1/chat/sessions/c", 405}, {"POST", "/api/v1/chat/sessions/c/model", 405}, {"POST", "/api/v1/chat/sessions/c/messages", 405}, {"GET", "/api/v1/chat/sessions/c/messages?beforeSeq=bad", 400},
		{"GET", "/api/v1/chat/sessions/c/rewind", 405}, {"POST", "/api/v1/chat/sessions/c/rewind?fromSequenceNo=", 400}, {"POST", "/api/v1/chat/sessions/c/export", 405}, {"GET", "/api/v1/chat/sessions/c/unknown", 404}, {"HEAD", "/api/v1/chat/sessions/c/export", 200},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, httptest.NewRequest(tc.method, tc.path, nil))
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
			if tc.method == "HEAD" && w.Body.Len() != 0 {
				t.Fatal("HEAD body")
			}
		})
	}
	for _, path := range []string{"/api/v1/chat/sessions/c/export", "/api/v1/chat/sessions/c/messages"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("Accept", "image/png")
		handler.ServeHTTP(w, r)
		if w.Code != 406 {
			t.Fatal(w.Code)
		}
	}
	w := httptest.NewRecorder()
	r := httptest.NewRequest("HEAD", "/", nil)
	if e := respond(w, r, 200, "value"); e != nil || w.Body.Len() != 0 {
		t.Fatal(e)
	}
	r = httptest.NewRequest("POST", "/", nil)
	r.Body = brokenRequestBody{}
	if _, e := optionalString(httptest.NewRecorder(), r, "title"); e == nil {
		t.Fatal("read failure ignored")
	}
	if _, e := NewHTTP(nil, time.UTC, logger); e == nil {
		t.Fatal("nil service")
	}
	for _, raw := range []string{"0xＡＦ", "0xａｆ"} {
		n, e := parseJavaInt(raw)
		if e != nil || n != 175 {
			t.Fatal(n, e)
		}
	}
	for _, field := range []string{"id", "filename", "contentType", "size"} {
		object := map[string]json.RawMessage{"size": json.RawMessage(`1`)}
		object[field] = json.RawMessage(`{}`)
		if field == "size" {
			object[field] = json.RawMessage(`"broken`)
		}
		if _, e := decodeAttachment(object); e == nil {
			t.Fatal("invalid field accepted", field)
		}
	}
}
func TestStorageHTTPPropagatesReadFailures(t *testing.T) {
	for _, tc := range []struct{ path, fail string }{{"/api/v1/chat/sessions", "sessions"}, {"/api/v1/chat/sessions/search", "sessions"}, {"/api/v1/chat/sessions/c/export", "session"}} {
		tx := &faultServiceTx{fail: tc.fail, session: Session{ConversationID: "c"}}
		svc, _ := NewService(oracleStore{tx}, nil)
		h := &HTTP{svc, time.UTC}
		if e := h.serve(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, tc.path, nil)); !errors.Is(e, serviceFailure) {
			t.Fatal(e)
		}
	}
}
