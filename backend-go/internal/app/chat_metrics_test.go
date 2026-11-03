package app

import (
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/chat"
	"strings"
	"testing"
	"time"
)

func TestChatActualMetricsLabelsTimersTokensAndRedaction(t *testing.T) {
	engine := &chat.Engine{}
	engine.Metrics.Active.Add(2)
	engine.Metrics.Measurements.Duration("openai", "fixture", "success", time.Second)
	engine.Metrics.Measurements.Duration("openai", "fixture", "error", 2*time.Second)
	engine.Metrics.Measurements.First("openai", "fixture", time.Millisecond)
	engine.Metrics.Measurements.Usage(chat.UsageRecord{Model: "fixture", Source: "ACTUAL", Prompt: 10, Completion: 2})
	m := &foundationMetrics{chat: &chat.Module{Engine: engine}}
	v, ok, e := m.chatMetric("personalos.chat.duration", url.Values{"tag": {"outcome:success"}})
	if e != nil || !ok || v.(actuatorMetric).Measurements[0].Value != 1 {
		t.Fatal(v, ok, e)
	}
	v, ok, e = m.chatMetric("personalos.chat.tokens", url.Values{"tag": {"direction:input"}})
	if e != nil || !ok || v.(actuatorMetric).Measurements[0].Value != 10 {
		t.Fatal(v, ok, e)
	}
	for _, q := range []url.Values{{"tag": {"model:no"}}, {"tag": {"outcome:success", "outcome:error"}}, {"tag": {"unknown:value"}}} {
		if _, ok, e = m.chatMetric("personalos.chat.duration", q); e != nil || ok {
			t.Fatal(q, e)
		}
	}
	w := httptest.NewRecorder()
	m.writeChatMetrics(w)
	s := w.Body.String()
	for _, want := range []string{"personalos_chat_streams_active 2", "personalos_chat_duration_seconds_bucket{", "personalos_chat_first_content_seconds_count{", "personalos_chat_tokens_total{"} {
		if !strings.Contains(s, want) {
			t.Fatal("missing", want, s)
		}
	}
	if _, ok, e = m.chatMetric("personalos.chat.duration", url.Values{"tag": {"bad"}}); e == nil || ok {
		t.Fatal("invalid tag accepted")
	}
}
