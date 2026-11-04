package auth

import (
	"io"
	"log/slog"
	"net/http"
	"testing"
	"time"
)

func TestP7CustomMCPExemptionKeepsAuthenticationAndOtherCSRF(t *testing.T) {
	store, _ := NewMemoryStore(DefaultSessionTimeout)
	service, e := newService(enabledOptions(), &testCodec{}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	layer, e := NewHTTP(service, store, HTTPOptions{CSRFEnabled: true, MCPPath: "/personal"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	handler := layer.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	for _, path := range []string{"/personal", "/mcp"} {
		if w := requestAuth(handler, "POST", path, `{}`, nil, ""); w.Code != 401 {
			t.Fatal(path, w.Code, w.Body.String())
		}
	}
	for _, path := range []string{"/personal/other", "/api/v1/mcp/servers/demo/reconnect", "/api/v1/import"} {
		if w := requestAuth(handler, "POST", path, `{}`, nil, ""); w.Code != 403 {
			t.Fatal(path, w.Code, w.Body.String())
		}
	}
}
