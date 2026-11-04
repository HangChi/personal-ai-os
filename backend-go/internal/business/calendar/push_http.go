package calendar

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

const pushPath = reminderPath + "/push"

func OwnsPushPath(path string) bool {
	return path == pushPath || path == pushPath+"/subscriptions"
}

func (p *Push) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		if r.URL.Path == pushPath {
			if r.Method != http.MethodGet && r.Method != http.MethodHead {
				return web.MethodNotSupported(r.Method, []string{"GET"})
			}
			var key *string
			if p.enabled {
				key = &p.public
			}
			if r.Method == http.MethodHead {
				w.Header().Set("Content-Type", media)
				w.WriteHeader(http.StatusOK)
				return nil
			}
			return api.WriteControllerMediaJSON(w, http.StatusOK, api.OK(map[string]any{"enabled": p.enabled, "publicKey": key}), media)
		}
		if r.Method != http.MethodPost && r.Method != http.MethodDelete {
			return web.MethodNotSupported(r.Method, []string{"POST", "DELETE"})
		}
		if r.ContentLength == 0 {
			return web.InvalidBody(nil)
		}
		object, err := web.DecodeJSONObject(w, r, 10<<20)
		if err != nil {
			return err
		}
		endpoint, err := requiredPushText(object, "endpoint", 2048)
		if err != nil {
			return err
		}
		if r.Method == http.MethodDelete {
			if err := p.Unsubscribe(r.Context(), endpoint); err != nil {
				return err
			}
			return api.WriteControllerMediaJSON(w, http.StatusOK, api.OK[any](nil), media)
		}
		var keys map[string]json.RawMessage
		if raw := object["keys"]; raw != nil {
			if err := json.Unmarshal(raw, &keys); err != nil {
				return web.InvalidBody(err)
			}
		}
		key, err := requiredPushText(keys, "p256dh", 128)
		if err != nil {
			return err
		}
		auth, err := requiredPushText(keys, "auth", 64)
		if err != nil {
			return err
		}
		id, err := p.Subscribe(r.Context(), endpoint, key, auth)
		if err != nil {
			return err
		}
		return api.WriteControllerMediaJSON(w, http.StatusOK, api.OK(id), media)
	}, logger)
}

func requiredPushText(object map[string]json.RawMessage, field string, limit int) (string, error) {
	value, err := web.JSONStringField(object, field)
	if err != nil {
		return "", err
	}
	if value == nil || strings.TrimSpace(*value) == "" || len(utf16.Encode([]rune(*value))) > limit {
		return "", validation("无效的推送订阅字段：" + field)
	}
	return *value, nil
}
