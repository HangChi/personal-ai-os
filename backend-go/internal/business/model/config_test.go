package model

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func ptr[T any](v T) *T { return &v }

func resolver(t *testing.T, overrides, environment map[string]string) *Resolver {
	t.Helper()
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides, System: environment})
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewResolver(p)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// 移植 ModelConfigServiceTest 的逐字段回落、来源徽章和语音无默认断言。
func TestResolveConfiguration(t *testing.T) {
	t.Run("yaml-defaults-are-not-environment", func(t *testing.T) {
		r := resolver(t, nil, nil).Resolve(Values{})
		if r.BaseURL != "https://api.openai.com" || r.ChatModel != "gpt-4o-mini" || r.EmbeddingModel != "text-embedding-3-small" || r.HasAPIKey() || r.HasAudioModel() || r.Revision != nil || r.UpdatedAt != nil {
			t.Fatal("default configuration mismatch")
		}
		for _, field := range []string{"baseUrl", "apiKey", "chatModel", "embeddingModel", "audioModel"} {
			if r.Sources[field] != Default {
				t.Errorf("%s source=%s", field, r.Sources[field])
			}
		}
		// 原 Java 温度按已解析值判断来源；YAML 的 0.7 同样标 ENVIRONMENT。
		if r.Temperature == nil || *r.Temperature != .7 || r.Sources["temperature"] != Environment {
			t.Fatal("temperature fallback mismatch")
		}
	})
	t.Run("per-field-not-whole-row", func(t *testing.T) {
		env := map[string]string{"OPENAI_BASE_URL": " https://env.invalid/v1 ", "OPENAI_API_KEY": "synthetic-env-only-abcd", "OPENAI_CHAT_MODEL": "env-chat", "OPENAI_EMBEDDING_MODEL": "env-embed"}
		r := resolver(t, nil, env).Resolve(Values{ChatModel: ptr("db-chat"), Temperature: ptr(Temperature(0)), Revision: ptr(3)})
		if r.BaseURL != "https://env.invalid/v1" || r.APIKey() != env["OPENAI_API_KEY"] || r.ChatModel != "db-chat" || r.EmbeddingModel != "env-embed" || *r.Temperature != 0 || *r.Revision != 3 {
			t.Fatal("per-field fallback mismatch")
		}
		if r.Sources["chatModel"] != Database || r.Sources["temperature"] != Database || r.Sources["baseUrl"] != Environment || r.Sources["apiKey"] != Environment {
			t.Fatal("source mismatch")
		}
	})
	t.Run("direct-property-does-not-invent-variable", func(t *testing.T) {
		r := resolver(t, map[string]string{"spring.ai.openai.base-url": "https://property.invalid", "spring.ai.openai.chat.temperature": "bad"}, nil).Resolve(Values{})
		if r.BaseURL != "https://property.invalid" || r.Sources["baseUrl"] != Default || r.Temperature != nil || r.Sources["temperature"] != Default {
			t.Fatal("original variable lookup mismatch")
		}
	})
	t.Run("blank-variable-and-java-whitespace", func(t *testing.T) {
		r := resolver(t, nil, map[string]string{"OPENAI_API_KEY": "\t\u3000"}).Resolve(Values{BaseURL: ptr("\u3000"), AudioModel: ptr("\t asr-fixture \u3000")})
		if r.HasAPIKey() || r.Sources["apiKey"] != Default || r.BaseURL != "https://api.openai.com" || r.AudioModel == nil || *r.AudioModel != "asr-fixture" || r.Sources["audioModel"] != Database {
			t.Fatal("blank fallback mismatch")
		}
		// Character.isWhitespace 不把 NBSP 当作空白；不能换成 strings.TrimSpace。
		r = resolver(t, nil, nil).Resolve(Values{APIKey: ptr("\u00a0"), BaseURL: ptr("  db-url  ")})
		if !r.HasAPIKey() || r.Sources["apiKey"] != Database || r.BaseURL != "  db-url  " {
			t.Fatal("database value was incorrectly stripped")
		}
	})
}

func TestDTOShapeAndSecretBoundary(t *testing.T) {
	now := time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC)
	values := Values{APIKey: ptr("synthetic-secret-only-abcd"), Revision: ptr(2), UpdatedAt: &now}
	r := resolver(t, nil, nil).Resolve(values)
	for _, raw := range []any{values, &values, r, &r} {
		encoded, err := json.Marshal(raw)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(encoded), "synthetic-secret") || strings.Contains(fmt.Sprintf("%+v %#v", raw, raw), "synthetic-secret") {
			t.Fatal("internal configuration leaked secret")
		}
	}
	dto := r.DTO()
	data, err := json.Marshal(dto)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	if len(result) != 11 || result["apiKeyMasked"] != "****abcd" || result["hasApiKey"] != true || result["hotReloadable"] != true || result["embeddingHotReloadable"] != false {
		t.Fatalf("DTO shape=%s", data)
	}
	if _, exists := result["apiKey"]; exists {
		t.Fatal("plaintext field in DTO")
	}
	for _, tc := range []struct{ key, want string }{{"", ""}, {"\u3000", ""}, {"abc", "****"}, {"abcd", "****"}, {"12345", "****2345"}, {"key😀ab", "****😀ab"}, {"key😀abc", "****?abc"}} {
		if got := Mask(tc.key); got != tc.want {
			t.Errorf("mask mismatch")
		}
	}
	// 每次回读拥有独立 map 和可空字段，不共享 DTO 可变状态。
	dto.Sources["apiKey"] = Default
	*dto.Revision = 99
	if r.Sources["apiKey"] != Database || *r.Revision != 2 || *values.Revision != 2 {
		t.Fatal("DTO mutated resolved configuration")
	}
}

func TestProfileModelsAndLegacyFallback(t *testing.T) {
	r := resolver(t, nil, nil)
	p := Profile{ID: "fixture", Name: "档案", Protocol: "ANTHROPIC", Values: Values{ChatModel: ptr(" old-chat "), EmbeddingModel: ptr(" old-embed "), AudioModel: ptr("asr"), Revision: ptr(1)}}
	d := r.ProfileDTO(p)
	if strings.Join(d.ChatModels, ",") != "old-chat" || strings.Join(d.EmbeddingModels, ",") != "old-embed" || len(d.AudioModels) != 0 || d.AudioModel != "asr" {
		t.Fatal("legacy fallback must only synthesize CHAT/EMBEDDING")
	}
	p.Models = []Model{{Kind: Audio, Name: "audio-only"}}
	d = r.ProfileDTO(p)
	if len(d.ChatModels) != 0 || len(d.EmbeddingModels) != 0 || len(d.AudioModels) != 1 {
		t.Fatal("partial child list must not merge legacy columns")
	}
	p.Models = []Model{{Kind: Chat, Name: "second", ContextWindow: ptr(32768)}, {Kind: Chat, Name: "first"}, {Kind: Embedding, Name: "embed", ContextWindow: ptr(8192)}, {Kind: "FUTURE", Name: "unknown", ContextWindow: ptr(4096)}}
	d = r.ProfileDTO(p)
	if strings.Join(d.ChatModels, ",") != "second,first,unknown" || len(d.ContextWindows) != 2 || d.ContextWindows["second"] != 32768 || d.ContextWindows["unknown"] != 4096 {
		t.Fatal("child order/kind/window mismatch")
	}
	p.Values = Values{}
	p.Models = nil
	d = r.ProfileDTO(p)
	encoded, err := json.Marshal(d)
	if err != nil {
		t.Fatal(err)
	}
	for _, fragment := range []string{`"chatModels":[]`, `"embeddingModels":[]`, `"audioModels":[]`, `"contextWindows":{}`, `"audioModel":""`, `"presetId":null`} {
		if !strings.Contains(string(encoded), fragment) {
			t.Errorf("missing %s", fragment)
		}
	}
}
