package workflow

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
)

const basePath = "/api/v1/workflows"

func OwnsPath(path string) bool { return path == basePath || strings.HasPrefix(path, basePath+"/") }
func (s *Service) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		value, e := s.serve(w, r)
		if e != nil {
			return e
		}
		if r.Method == "HEAD" {
			w.Header().Set("Content-Type", media)
			return nil
		}
		return api.WriteControllerMediaJSON(w, 200, api.OK(value), media)
	}, logger)
}
func (s *Service) serve(w http.ResponseWriter, r *http.Request) (any, error) {
	path := strings.TrimPrefix(r.URL.Path, basePath)
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	method := r.Method
	change := func() (Change, error) {
		var c Change
		obj, e := web.DecodeJSONObject(w, r, 16384)
		if e != nil {
			return c, e
		}
		raw, e := json.Marshal(obj)
		if e == nil {
			e = json.Unmarshal(raw, &c)
		}
		if e != nil {
			return c, web.InvalidBody(e)
		}
		return c, nil
	}
	if path == "" {
		if method == "GET" || method == "HEAD" {
			return s.List(r.Context())
		}
		if method == "POST" {
			c, e := change()
			if e != nil {
				return nil, e
			}
			return s.Save(r.Context(), "", c)
		}
		return nil, web.MethodNotSupported(method, []string{"GET", "POST"})
	}
	if len(parts) == 3 && parts[0] == "runs" && parts[2] == "retry" {
		id, ok := api.ParseJavaUUID(parts[1])
		if !ok {
			return nil, web.InvalidParameter("id")
		}
		if method != "POST" {
			return nil, web.MethodNotSupported(method, []string{"POST"})
		}
		return s.Retry(r.Context(), id)
	}
	id, ok := api.ParseJavaUUID(parts[0])
	if !ok {
		return nil, web.InvalidParameter("id")
	}
	if len(parts) == 1 {
		if method != "PATCH" {
			return nil, web.MethodNotSupported(method, []string{"PATCH"})
		}
		c, e := change()
		if e != nil {
			return nil, e
		}
		return s.Save(r.Context(), id, c)
	}
	if len(parts) == 2 && parts[1] == "runs" {
		if method != "GET" && method != "HEAD" {
			return nil, web.MethodNotSupported(method, []string{"GET"})
		}
		return s.History(r.Context(), id)
	}
	if len(parts) == 2 && parts[1] == "run" {
		if method != "POST" {
			return nil, web.MethodNotSupported(method, []string{"POST"})
		}
		return s.Run(r.Context(), id)
	}
	return nil, web.NotFound()
}
