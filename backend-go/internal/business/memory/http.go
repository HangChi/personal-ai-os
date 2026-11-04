package memory

import (
	"log/slog"
	"net/http"
	"strings"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

const path = "/api/v1/memories"

func OwnsPath(value string) bool {
	return value == path || strings.HasPrefix(value, path+"/")
}

func NewHTTP(store *Store, logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		value, err := serve(store, w, r)
		if err != nil {
			return err
		}
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", media)
			w.WriteHeader(http.StatusOK)
			return nil
		}
		return api.WriteControllerMediaJSON(w, http.StatusOK, api.OK(value), media)
	}, logger)
}

func serve(store *Store, w http.ResponseWriter, r *http.Request) (any, error) {
	ctx := r.Context()
	if r.URL.Path == path {
		switch r.Method {
		case http.MethodGet, http.MethodHead:
			return store.List(ctx)
		case http.MethodPost:
			content, err := readContent(w, r)
			if err != nil {
				return nil, err
			}
			return store.Create(ctx, content)
		default:
			return nil, web.MethodNotSupported(r.Method, []string{"GET", "POST"})
		}
	}
	id, action, nested := strings.Cut(strings.TrimPrefix(r.URL.Path, path+"/"), "/")
	if id == "" || (nested && action != "pin") {
		return nil, web.NotFound()
	}
	if nested {
		if r.Method != http.MethodPatch {
			return nil, web.MethodNotSupported(r.Method, []string{"PATCH"})
		}
		object, err := web.DecodeOptionalJSONObject(w, r, 10<<20)
		if err != nil {
			return nil, err
		}
		if object == nil {
			return nil, api.NewBusinessError(api.ValidationFailed, "pinned 不能为空")
		}
		pinned, err := web.JSONBoolField(object, "pinned")
		if err != nil {
			return nil, err
		}
		return store.Pin(ctx, id, pinned != nil && *pinned)
	}
	switch r.Method {
	case http.MethodPatch:
		content, err := readContent(w, r)
		if err != nil {
			return nil, err
		}
		return store.Update(ctx, id, content)
	case http.MethodDelete:
		return nil, store.Delete(ctx, id)
	default:
		return nil, web.MethodNotSupported(r.Method, []string{"PATCH", "DELETE"})
	}
}

func readContent(w http.ResponseWriter, r *http.Request) (string, error) {
	object, err := web.DecodeOptionalJSONObject(w, r, 10<<20)
	if err != nil {
		return "", err
	}
	content, err := web.JSONStringField(object, "content")
	if content == nil || err != nil {
		return "", err
	}
	return *content, nil
}
