package model

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
)

func TestModelModuleAssemblyContract(t *testing.T) {
	for _, provider := range []string{"none", "openai", "ollama", "unsupported"} {
		t.Run(provider, func(t *testing.T) {
			p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.model.chat": provider, "spring.ai.model.embedding": provider, "spring.ai.openai.base-url": "https://fixture.invalid", "spring.ai.openai.api-key": "synthetic-key", "app.redis.enabled": "false"}})
			if e != nil {
				t.Fatal(e)
			}
			steps := []sqlStep{queryStep("bool_and", sqlValues(true)), queryStep("has_sequence_privilege", sqlValues(true))}
			if provider != "unsupported" {
				steps = append(steps, queryStep("FROM ai_model_config"))
			}
			m, e := OpenModule(context.Background(), contractPool(t, steps...), p, nil, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if provider == "unsupported" {
				if e == nil {
					t.Fatal("unsupported provider")
				}
				return
			}
			if e != nil || m.Handler == nil {
				t.Fatal(m, e)
			}
			if provider != "none" && (m.Embedding == nil || !m.Runtime.Configured()) {
				t.Fatal("provider absent")
			}
		})
	}
}
func TestModelHTTPServiceDelegationContract(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"GET", modelPath, "", 200}, {"HEAD", modelPath, "", 200}, {"PUT", modelPath, `{"baseUrl":"https://fixture.invalid","temperature":0.5}`, 200}, {"GET", modelPath + "/profiles", "", 200}, {"POST", modelPath + "/profiles", `{"name":"新配置","chatModels":["chat"],"contextWindows":{"chat":4096}}`, 200}, {"PUT", modelPath + "/profiles/" + id, `{"name":"变更"}`, 200}, {"POST", modelPath + "/profiles/" + id + "/activate", "{}", 200}, {"DELETE", modelPath + "/profiles/" + id, "", 200}, {"PUT", modelPath + "/profiles/order", `{"ids":[null,"10000000-0000-4000-8000-000000000001"]}`, 200}, {"POST", modelPath + "/test", "{}", 200}, {"PATCH", modelPath, "{}", 405}, {"DELETE", modelPath + "/profiles", "", 405}, {"GET", modelPath + "/profiles/invalid", "", 400}, {"GET", modelPath + "/profiles/" + id + "/unknown", "", 404}, {"GET", modelPath + "/profiles/" + id + "/extra/nested", "", 404}, {"GET", modelPath + "/profiles/order", "", 405}, {"GET", modelPath + "/test", "", 405}, {"PUT", modelPath, `{"baseUrl":{}}`, 400}, {"PUT", modelPath, `{"temperature":"invalid"}`, 400}, {"POST", modelPath + "/profiles", `{"name":{}}`, 400}, {"POST", modelPath + "/profiles", `{"chatModels":{}}`, 400}, {"POST", modelPath + "/profiles", `{"chatModels":[{}]}`, 400}, {"POST", modelPath + "/profiles", `{"contextWindows":[]}`, 400}, {"POST", modelPath + "/profiles", `{"contextWindows":{"x":"NaN"}}`, 400}, {"PUT", modelPath + "/profiles/order", `{"ids":["bad"]}`, 400}, {"POST", modelPath + "/profiles/probe-window", `{"profileId":"bad"}`, 400}, {"GET", modelPath + "/profiles/probe-models", "", 405}, {"GET", "/unknown", "", 404},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			runtime := NewRuntime(nil)
			service, store := modelWriteFixture(t, runtime)
			store.profile.ID = id
			h := NewHTTP(service, NewProbe(nil), NewAudioService(service.ReadService, nil), slog.New(slog.NewTextHandler(io.Discard, nil)))
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
			if strings.Contains(w.Body.String(), "synthetic-contract-key") {
				t.Fatal("API key leak")
			}
		})
	}
}
