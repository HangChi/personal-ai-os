package mcp

import (
	"log/slog"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
)

type HTTP struct {
	Registry *Registry
	Personal PersonalOptions
	Protocol http.Handler
}

func (h *HTTP) OwnsPath(p string) bool {
	return p == "/api/v1/mcp/server" || p == "/api/v1/mcp/servers" || strings.HasPrefix(p, "/api/v1/mcp/servers/") || p == h.Personal.Endpoint
}
func (h *HTTP) Handler(logger *slog.Logger) http.Handler { return web.HandleErrors(h.serve, logger) }
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) error {
	if r.URL.Path == h.Personal.Endpoint {
		h.Protocol.ServeHTTP(w, r)
		return nil
	}
	var value any
	switch {
	case r.URL.Path == "/api/v1/mcp/servers":
		if r.Method != "GET" && r.Method != "HEAD" {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		value = struct {
			Enabled bool           `json:"enabled"`
			Servers []ServerStatus `json:"servers"`
		}{h.Registry.Enabled(), h.Registry.Statuses()}
	case r.URL.Path == "/api/v1/mcp/server":
		if r.Method != "GET" && r.Method != "HEAD" {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		value = h.Personal.Status()
	case strings.HasPrefix(r.URL.Path, "/api/v1/mcp/servers/") && strings.HasSuffix(r.URL.Path, "/reconnect"):
		if r.Method != "POST" {
			return web.MethodNotSupported(r.Method, []string{"POST"})
		}
		name := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/v1/mcp/servers/"), "/reconnect")
		if name == "" || strings.Contains(name, "/") {
			return web.NotFound()
		}
		if e := h.Registry.Reconnect(r.Context(), name); e != nil {
			return e
		}
		for _, s := range h.Registry.Statuses() {
			if s.Name == name {
				value = s
			}
		}
	default:
		return web.NotFound()
	}
	media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
	if !ok {
		return web.NotAcceptable()
	}
	if r.Method == "HEAD" {
		w.Header().Set("Content-Type", media)
		return nil
	}
	return api.WriteControllerMediaJSON(w, 200, api.OK(value), media)
}
