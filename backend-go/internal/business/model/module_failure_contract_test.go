package model

import (
	"context"
	"errors"
	"personal-ai-os/backend-go/internal/platform/config"
	"testing"
)

func TestModelAssemblyRejectsBadConfigurationAtItsBoundary(t *testing.T) {
	unresolved := "$" + "{contract.missing}"
	for _, tc := range []struct {
		key, value, chat, embedding string
		stage                       int
	}{
		{"spring.ai.retry.max-attempts", "2", "none", "none", 0},
		{"spring.ai.retry.max-attempts", unresolved, "none", "none", 0},
		{"app.model-key-encryption-key", unresolved, "none", "none", 0},
		{"app.model-key-encryption-key", "bad-key", "none", "none", 0},
		{"spring.ai.openai.chat.model", unresolved, "none", "none", 0},
		{"spring.ai.model.chat", unresolved, "none", "none", 1},
		{"spring.ai.ollama.base-url", unresolved, "ollama", "none", 1},
		{"spring.ai.ollama.chat.model", unresolved, "ollama", "none", 1},
		{"spring.ai.ollama.chat.temperature", unresolved, "ollama", "none", 1},
		{"spring.ai.ollama.base-url", "invalid", "ollama", "none", 1},
		{"spring.ai.openai.chat.options.extra-body.invalid", unresolved, "openai", "none", 1},
		{"spring.ai.model.embedding", unresolved, "none", "none", 2},
		{"spring.ai.model.embedding", "unsupported", "none", "none", 2},
		{"app.redis.enabled", unresolved, "none", "none", 2},
		{"app.redis.enabled", "perhaps", "none", "none", 2},
		{"app.redis.enabled", "true", "none", "none", 2},
		{"spring.ai.ollama.base-url", unresolved, "none", "ollama", 2},
		{"spring.ai.ollama.embedding.model", unresolved, "none", "ollama", 2},
		{"spring.ai.ollama.base-url", "invalid", "none", "ollama", 2},
		{"app.embedding-cache.enabled", unresolved, "none", "openai", 2},
		{"app.embedding-cache.enabled", "perhaps", "none", "openai", 2},
		{"app.embedding-cache.capacity", unresolved, "none", "openai", 2},
		{"app.embedding-cache.capacity", "invalid", "none", "openai", 2},
		{"app.embedding-cache.capacity", "0", "none", "openai", 2},
		{"app.embedding-cache.ttl", unresolved, "none", "openai", 2},
		{"app.embedding-cache.ttl", "invalid", "none", "openai", 2},
		{"app.embedding-cache.ttl", "", "none", "openai", 2},
		{"app.embedding-cache.revision", unresolved, "none", "openai", 2},
	} {
		t.Run(tc.key+tc.value+tc.chat+tc.embedding, func(t *testing.T) {
			overrides := map[string]string{"spring.ai.model.chat": tc.chat, "spring.ai.model.embedding": tc.embedding, "app.redis.enabled": "false",
				"spring.ai.openai.base-url": "https://fixture.invalid/v1", "spring.ai.openai.api-key": "synthetic-only",
				"spring.ai.openai.chat.model": "fixture-chat", "spring.ai.openai.embedding.model": "fixture-embed"}
			overrides[tc.key] = tc.value
			p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			var steps []sqlStep
			if tc.stage > 0 {
				steps = append(steps, queryStep("bool_and", sqlValues(true)), queryStep("has_sequence_privilege", sqlValues(true)))
			}
			if tc.stage > 1 {
				steps = append(steps, queryStep("FROM ai_model_config"))
			}
			m, e := OpenModule(context.Background(), contractPool(t, steps...), p, nil, nil, nil)
			if e == nil || m != nil {
				t.Fatal("invalid configuration accepted", m, e)
			}
		})
	}
	if _, e := OpenModule(context.Background(), nil, nil, nil, nil, nil); e == nil {
		t.Fatal("missing dependencies")
	}
}
func TestModelPrivilegeQueryErrorsArePropagated(t *testing.T) {
	for fail := 0; fail < 2; fail++ {
		steps := []sqlStep{queryStep("bool_and", sqlValues(true)), queryStep("has_sequence_privilege", sqlValues(true))}
		sentinel := errors.New("private database failure")
		steps[fail].err = sentinel
		steps = steps[:fail+1]
		if e := CheckPrivileges(context.Background(), contractPool(t, steps...)); !errors.Is(e, sentinel) {
			t.Fatal(e)
		}
	}
}
