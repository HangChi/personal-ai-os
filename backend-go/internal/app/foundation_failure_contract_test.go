package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"personal-ai-os/backend-go/internal/platform/config"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
	"strings"
	"testing"
	"time"
)

func TestFoundationConfigurationFailuresCleanUpPartialAssembly(t *testing.T) {
	for _, tc := range []struct{ key, value string }{
		{"app.retention.token-usage-days", "-1"}, {"app.retention.cron", "invalid"},
		{"management.metrics.distribution.percentiles-histogram.http.server.requests", "bad"},
		{"app.calendar.scan-interval-ms", "bad"},
		{"app.calendar.stream-check-interval-ms", "0"},
		{"app.memory.ranking.timeout", "bad"},
		{"app.knowledge.rag.budget-ms", "0"},
		{"app.chat.max-attachment-bytes", "bad"},
		{"app.ai.system-prompt", "${contract.missing}"},
		{"app.ai.memory.max-messages", "bad"},
		{"app.context.summary.timeout", "bad"},
		{"app.context.protection.max-retries", "bad"},
		{"app.plan.enabled", "maybe"},
		{"app.agent.max-model-calls", "0"},
		{"app.chat.stream-timeout", "0ms"},
		{"app.workflows.enabled", "maybe"},
		{"app.maintenance.heartbeat-enabled", "maybe"},
		{"app.rabbitmq.uri", "invalid"},
	} {
		t.Run(tc.key, func(t *testing.T) {
			overrides := map[string]string{"app.runtime.role": "combined", "app.auth.enabled": "false", "app.calendar.scan-enabled": "false", "app.memory.backfill.enabled": "false", "app.context.auto-memory.enabled": "false", "app.context.summary.enabled": "false", "app.chat.upload-dir": t.TempDir(), "app.chat.attachment-janitor-enabled": "false", "app.retention.cron": "-", "app.maintenance.heartbeat-enabled": "false", "app.workflows.enabled": "false", "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none", tc.key: tc.value}
			if tc.key == "app.rabbitmq.uri" {
				overrides["app.rabbitmq.enabled"] = "true"
			}
			props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			f, e := OpenFoundation(context.Background(), props, FoundationOptions{Connector: foundationContractConnector(t, false), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true, EnableP5: true, EnableP6: true, EnableP7: true, EnableP8: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if e == nil || f != nil {
				if f != nil {
					_ = f.Close(context.Background())
				}
				t.Fatal("invalid configuration accepted", tc.key, e)
			}
			if strings.Contains(e.Error(), "unexpected foundation") {
				t.Fatal("test oracle did not reach intended boundary", e)
			}
		})
	}
}

func TestP5DeliveryConfigurationFailureBoundaries(t *testing.T) {
	for _, tc := range []struct {
		key, value string
		flags      map[string]string
	}{
		{"app.calendar.stream-check-interval-ms", "0", nil},
		{"app.calendar.stream-check-interval-ms", "$" + "{contract.missing}", nil},
		{"app.rabbitmq.uri", "", map[string]string{"app.rabbitmq.enabled": "true"}},
		{"app.rabbitmq.uri", "$" + "{contract.missing}", map[string]string{"app.rabbitmq.enabled": "true"}},
		{"app.web-push.public-key", "$" + "{contract.missing}", map[string]string{"app.web-push.enabled": "true"}},
		{"app.web-push.interval-ms", "0", map[string]string{"app.web-push.enabled": "true"}},
		{"app.web-push.public-key", "bad", map[string]string{"app.web-push.enabled": "true"}},
		{"app.reminders.webhook.url", "$" + "{contract.missing}", map[string]string{"app.reminders.webhook.enabled": "true"}},
		{"app.reminders.webhook.url", "ftp://invalid", map[string]string{"app.reminders.webhook.enabled": "true"}},
		{"app.reminders.webhook.poll-ms", "0", map[string]string{"app.reminders.webhook.enabled": "true"}},
	} {
		t.Run(tc.key+tc.value, func(t *testing.T) {
			props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.reminders.webhook.secret": "01234567890123456789012345678901", "app.reminders.webhook.url": "https://example.test/reminder", tc.key: tc.value}})
			if e != nil {
				t.Fatal(e)
			}
			p := &foundationP5{role: config.Combined}
			if e = p.configureDelivery(props, tc.flags); e == nil {
				t.Fatal("invalid delivery config accepted", tc.key)
			}
			if p.push != nil {
				p.push.Close()
			}
			if p.webhook != nil {
				p.webhook.Close()
			}
		})
	}
	props, e := config.LoadBaselineProperties(config.PropertyOptions{})
	if e != nil {
		t.Fatal(e)
	}
	if got, e := p5Interval(props, "missing.interval", time.Second); e != nil || got != time.Second {
		t.Fatal(got, e)
	}
	if sessionCookie(true) != "SESSION" || sessionCookie(false) != "JSESSIONID" {
		t.Fatal("cookie contract")
	}
}

func TestFoundationDiagnosticHeadAndUnsupportedMethod(t *testing.T) {
	// Run the actual worker diagnostics handler without SQL or servlet fixtures.
	props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.auth.enabled": "false"}})
	if e != nil {
		t.Fatal(e)
	}
	f, e := OpenFoundation(context.Background(), props, FoundationOptions{Connector: foundationContractConnector(t, false), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close(context.Background())
	handler := f.Handler()
	for _, method := range []string{http.MethodHead, http.MethodPost} {
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(method, "/livez", nil))
		if method == http.MethodHead && w.Body.Len() != 0 {
			t.Fatal("HEAD body")
		}
		if method == http.MethodPost && w.Code != 405 {
			t.Fatal(w.Code)
		}
	}
}

func TestFoundationRejectsInvalidDependenciesBeforeConnecting(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	props, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.auth.enabled": "false"}})
	if e != nil {
		t.Fatal(e)
	}
	options := func() FoundationOptions {
		return FoundationOptions{Connector: foundationContractConnector(t, false), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public"}
	}
	for _, change := range []func(*FoundationOptions){
		func(o *FoundationOptions) { o.Connector = nil }, func(o *FoundationOptions) { o.Migrations = nil }, func(o *FoundationOptions) { o.EnableP4 = true }, func(o *FoundationOptions) { o.EnableP5 = true }, func(o *FoundationOptions) { o.EnableP6 = true }, func(o *FoundationOptions) { o.EnableP7 = true }, func(o *FoundationOptions) { o.EnableP8 = true }, func(o *FoundationOptions) { o.ContainerNetwork = true },
	} {
		o := options()
		change(&o)
		if f, e := OpenFoundation(context.Background(), props, o, logger); e == nil || f != nil {
			t.Fatal("invalid dependency accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e := OpenFoundation(ctx, props, options(), logger); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	client, e := redisclient.New(redisclient.Config{URI: "redis://127.0.0.1:1"})
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	o := options()
	o.CandidateRedis = client
	if _, e := OpenFoundation(context.Background(), props, o, logger); e == nil {
		t.Fatal("candidate cache without P6 accepted")
	}
	for _, v := range []map[string]string{{"server.shutdown": "invalid"}, {"logging.level.root": "invalid"}, {"spring.servlet.multipart.max-file-size": "bad"}, {"server.servlet.session.timeout": "bad"}, {"app.shared-session.enabled": "true", "app.rate-limit.redis.enabled": "true"}} {
		p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: v})
		if e != nil {
			t.Fatal(e)
		}
		o := options()
		if v["app.shared-session.enabled"] == "true" {
			o.SessionRedis = client
			o.RateRedis = client
		}
		if f, e := OpenFoundation(context.Background(), p, o, logger); e == nil || f != nil {
			t.Fatal("invalid properties accepted", v)
		}
	}
	var f Foundation
	if e := f.Close(nil); e == nil {
		t.Fatal("nil close context")
	}
	for _, v := range []any{FoundationOptions{}, &f} {
		for _, format := range []string{"%v", "%#v"} {
			if strings.Contains(fmt.Sprintf(format, v), "Connector") {
				t.Fatal("configuration exposed")
			}
		}
	}
	if data, e := json.Marshal(FoundationOptions{}); e != nil || string(data) != `{"redacted":true}` {
		t.Fatal(string(data), e)
	}
}
