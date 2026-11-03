package model

import (
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
)

func TestProtocolRetryOverridesFailClosed(t *testing.T) {
	p, err := config.LoadBaselineProperties(config.PropertyOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if err = validateProtocolRetries(p); err != nil {
		t.Fatal("baseline single attempt rejected", err)
	}
	for key, bad := range map[string]string{"spring.ai.retry.max-attempts": "2", "spring.ai.openai.max-retries": "1", "spring.ai.openai.chat.max-retries": "1", "spring.ai.openai.embedding.max-retries": "synthetic-private-invalid"} {
		p, err = config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{key: bad}})
		if err != nil {
			t.Fatal(err)
		}
		if err = validateProtocolRetries(p); err == nil || strings.Contains(err.Error(), bad) {
			t.Fatal("retry override ignored/leaked", key, err)
		}
	}
}
