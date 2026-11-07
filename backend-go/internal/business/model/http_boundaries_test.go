package model

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
	"testing"
)

func TestModelHTTPMalformedAndStorageFailures(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	for _, tc := range []struct {
		method, path, body string
		fail               bool
		status             int
	}{
		{"GET", modelPath, "", true, 500}, {"PUT", modelPath, "{}", true, 500},
		{"GET", modelPath + "/profiles", "", true, 500}, {"PUT", modelPath + "/profiles/order", `{"ids":[]}`, true, 500},
		{"PUT", modelPath + "/profiles/order", "{", false, 400}, {"PUT", modelPath + "/profiles/order", `{"ids":{}}`, false, 400},
		{"POST", modelPath + "/profiles/probe-models", "{", false, 400}, {"POST", modelPath + "/profiles/probe-models", `{"model":{}}`, false, 400},
		{"POST", modelPath + "/profiles/probe-models", `{"preferredModels":{}}`, false, 400},
		{"POST", modelPath + "/profiles/probe-models", `{"preferredModels":[null,"chat"]}`, false, 200},
		{"POST", modelPath + "/profiles/probe-window", `{"model":""}`, false, 400},
		{"POST", modelPath + "/profiles/probe-models", `{"profileId":"` + id + `"}`, true, 500},
		{"GET", modelPath + "/profiles/" + id + "/activate", "", false, 405},
		{"POST", modelPath + "/profiles/" + id + "/activate", "", true, 500},
		{"PUT", modelPath + "/profiles/" + id, "{", false, 400},
		{"PUT", modelPath + "/profiles/" + id, `{"name":{}}`, false, 400},
		{"PUT", modelPath + "/profiles/" + id, `{"name":"ok"}`, true, 500},
		{"GET", modelPath + "/profiles/" + id, "", false, 405},
		{"POST", "/api/v1/chat/transcribe/status", "", false, 405},
		{"GET", "/api/v1/chat/transcribe/status", "", true, 500},
		{"GET", "/api/v1/chat/transcribe", "", false, 405},
		{"POST", "/api/v1/chat/transcribe", "{}", false, 500},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			s, store := modelWriteFixture(t, NewRuntime(nil))
			store.profile.ID = id
			if tc.fail {
				e := errors.New("private credentials")
				store.err = e
				store.writeErr = e
				store.requireErr = e
			}
			p := NewProbe(boundaryTransport(func(*http.Request) (*http.Response, error) { return boundaryResponse(200, `{"data":[]}`), nil }))
			h := NewHTTP(s, p, NewAudioService(s.ReadService, nil), slog.New(slog.NewTextHandler(io.Discard, nil)))
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			web.MultipartLifecycle(h, web.MultipartLimits{MaxFileBytes: 8 << 20, MaxRequestBytes: 9 << 20}).ServeHTTP(w, r)
			if w.Code != tc.status || strings.Contains(w.Body.String(), "private credentials") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	s, _ := modelWriteFixture(t, nil)
	h := &HTTP{service: s}
	r := httptest.NewRequest("GET", modelPath, nil)
	r.Header.Set("Accept", "image/png")
	if e := h.serve(httptest.NewRecorder(), r); e == nil {
		t.Fatal("accept")
	}
	obj := map[string]json.RawMessage{"contextWindows": json.RawMessage(`{"chat":null}`)}
	if c, e := decodeProfile(obj); e != nil || c.ContextWindows["chat"] != nil {
		t.Fatal(c, e)
	}
}
func TestAudioHTTPEmptyMultipartAndTranscriptionReadFailure(t *testing.T) {
	for _, mode := range []string{"missing", "empty", "store"} {
		s, store := modelWriteFixture(t, nil)
		if mode == "store" {
			store.err = errors.New("private audio credentials")
		}
		h := NewHTTP(s, NewProbe(nil), NewAudioService(s.ReadService, nil), slog.New(slog.NewTextHandler(io.Discard, nil)))
		var b bytes.Buffer
		mw := multipart.NewWriter(&b)
		if mode != "missing" {
			part, e := mw.CreateFormFile("file", "audio.webm")
			if e != nil {
				t.Fatal(e)
			}
			if mode == "store" {
				part.Write([]byte{1})
			}
		}
		mw.Close()
		r := httptest.NewRequest("POST", "/api/v1/chat/transcribe", &b)
		r.Header.Set("Content-Type", mw.FormDataContentType())
		w := httptest.NewRecorder()
		web.MultipartLifecycle(h, web.MultipartLimits{MaxFileBytes: 8 << 20, MaxRequestBytes: 9 << 20}).ServeHTTP(w, r)
		want := 400
		if mode == "store" {
			want = 500
		}
		if w.Code != want {
			t.Fatal(mode, w.Code, w.Body.String())
		}
	}
}
