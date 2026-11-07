package mcp

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMCPHTTPRouteMethodsNegotiationAndReconnectStatus(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	r, e := OpenRegistry(context.Background(), Options{Enabled: true, Servers: map[string]ServerConfig{"offline": {Command: "/nonexistent/offline-mcp-test", Timeout: time.Second}}}, logger)
	if e != nil {
		t.Fatal(e)
	}
	defer r.Close()
	protocolCalls := 0
	h := &HTTP{Registry: r, Personal: PersonalOptions{Endpoint: "/personal"}, Protocol: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { protocolCalls++; w.WriteHeader(204) })}
	handler := h.Handler(logger)
	for _, tc := range []struct {
		method, path, accept string
		want                 int
	}{
		{"GET", "/personal", "", 204},
		{"GET", "/api/v1/mcp/server", "", 200},
		{"HEAD", "/api/v1/mcp/server", "", 200},
		{"POST", "/api/v1/mcp/server", "", 405},
		{"POST", "/api/v1/mcp/servers", "", 405},
		{"GET", "/api/v1/mcp/servers", "text/plain", 406},
		{"GET", "/api/v1/mcp/servers/offline/reconnect", "", 405},
		{"POST", "/api/v1/mcp/servers/offline/reconnect", "", 200},
		{"POST", "/api/v1/mcp/servers/unknown/reconnect", "", 404},
		{"POST", "/api/v1/mcp/servers//reconnect", "", 404},
		{"POST", "/api/v1/mcp/servers/a/b/reconnect", "", 404},
		{"GET", "/api/v1/mcp/servers/unknown", "", 404},
	} {
		request := httptest.NewRequest(tc.method, tc.path, nil)
		request.Header.Set("Accept", tc.accept)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, request)
		if w.Code != tc.want {
			t.Fatal(tc, w.Code, w.Body.String())
		}
		if tc.method == "HEAD" && w.Body.Len() != 0 {
			t.Fatal("HEAD body")
		}
	}
	if protocolCalls != 1 {
		t.Fatal(protocolCalls)
	}
}

func TestPersonalToolsRejectMalformedArgumentsAndTimesBeforeStorage(t *testing.T) {
	s := PersonalServices{}
	for _, tc := range []struct{ name, raw string }{
		{"personal_list_tasks", "{"}, {"personal_list_tasks", `{"status":"invalid"}`},
		{"personal_search_knowledge", `{}`}, {"personal_list_calendar", `{"from":"bad"}`},
		{"personal_list_calendar", `{"to":"bad"}`}, {"unknown", `{"page":2,"size":5}`},
	} {
		if _, e := s.call(context.Background(), tc.name, []byte(tc.raw)); e == nil {
			t.Fatal(tc)
		}
	}
	value := " 2026-11-07T01:02:03Z "
	if v, e := parseInstant(&value); e != nil || v == nil || v.Year() != 2026 {
		t.Fatal(v, e)
	}
}
