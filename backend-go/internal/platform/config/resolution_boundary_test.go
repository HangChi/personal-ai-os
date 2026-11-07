package config

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"testing"
)

func boundaryProperties(t *testing.T, values map[string]string) *Properties {
	t.Helper()
	p, err := LoadBaselineProperties(PropertyOptions{Overrides: values})
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestUnresolvedConfigurationFailsAtConsumer(t *testing.T) {
	tests := []struct {
		key  string
		read func(*Properties) error
	}{
		{"logging.structured.format.console", func(p *Properties) error { _, e := p.Logging(); return e }},
		{"logging.level.root", func(p *Properties) error { _, e := p.Logging(); return e }},
		{"management.metrics.distribution.percentiles-histogram.personalos.knowledge.recall", func(p *Properties) error { _, e := p.Actuator(); return e }},
		{"info.app.name", func(p *Properties) error { _, e := p.Actuator(); return e }},
		{"app.name", func(p *Properties) error { _, e := p.Foundation(); return e }},
		{"spring.servlet.multipart.location", func(p *Properties) error { _, e := p.Multipart(); return e }},
		{"spring.servlet.multipart.enabled", func(p *Properties) error { _, e := p.Multipart(); return e }},
		{"spring.servlet.multipart.max-file-size", func(p *Properties) error { _, e := p.Multipart(); return e }},
		{"app.retention.token-usage-days", func(p *Properties) error { _, e := p.Retention(); return e }},
		{"app.retention.cron", func(p *Properties) error { _, e := p.Retention(); return e }},
		{"server.servlet.session.timeout", func(p *Properties) error { _, e := p.Session(false); return e }},
		{"mcp.enabled", func(p *Properties) error { _, e := p.MCP(); return e }},
		{"server.port", func(p *Properties) error { _, e := p.ProcessRuntime(); return e }},
		{"server.shutdown", func(p *Properties) error { return p.ValidateFoundationExtensions() }},
		{"vendor.option", func(p *Properties) error { _, e := p.Prefix("vendor."); return e }},
	}
	for _, tt := range tests {
		t.Run(tt.key, func(t *testing.T) {
			p := boundaryProperties(t, map[string]string{tt.key: "$" + "{missing-private-key}"})
			if e := tt.read(p); e == nil || strings.Contains(e.Error(), "missing-private-key") {
				t.Fatalf("unresolved value must fail without leaking: %v", e)
			}
		})
	}
}

func TestConfigurationDefaultsAndLoggingLevels(t *testing.T) {
	for name, want := range map[string]slog.Level{"TRACE": -8, "DEBUG": slog.LevelDebug, "INFO": slog.LevelInfo, "WARN": slog.LevelWarn, "ERROR": slog.LevelError, "OFF": 100} {
		got, e := boundaryProperties(t, map[string]string{"logging.level.root": name}).Logging()
		if e != nil || got.Level != want {
			t.Fatalf("level %s: %v %v", name, got, e)
		}
	}
	got, e := (&Properties{}).Multipart()
	if e != nil || got.MaxFileBytes != 1<<20 || got.MaxRequestBytes != 10<<20 {
		t.Fatalf("defaults: %+v %v", got, e)
	}
	for value, want := range map[string]http.SameSite{"lax": http.SameSiteLaxMode, "none": http.SameSiteNoneMode, "": http.SameSiteDefaultMode} {
		got, e := boundaryProperties(t, map[string]string{"server.servlet.session.cookie.same-site": value}).Session(false)
		if e != nil || got.Cookie.SameSite != want {
			t.Fatalf("same site %q: %+v %v", value, got, e)
		}
	}
	if _, e := boundaryProperties(t, map[string]string{"server.servlet.session.cookie.http-only": "invalid"}).Session(false); e == nil {
		t.Fatal("invalid cookie boolean accepted")
	}
	for _, values := range []map[string]string{{"app.rate-limit.capacity": "bad"}, {"spring.profiles.active": "prod"}} {
		if _, e := boundaryProperties(t, values).Foundation(); e == nil {
			t.Fatal("invalid foundation accepted", values)
		}
	}
	var nilProperties *Properties
	if _, e := nilProperties.Actuator(); e == nil {
		t.Fatal("nil actuator accepted")
	}
	if e := nilProperties.ValidateFoundationExtensions(); e == nil {
		t.Fatal("nil extensions accepted")
	}
	p := boundaryProperties(t, map[string]string{"vendor.": "ignored", "vendor.option": "kept"})
	values, e := p.Prefix("vendor.")
	if e != nil || len(values) != 1 || values["option"] != "kept" {
		t.Fatalf("prefix: %v %v", values, e)
	}
	secret := ActuatorProperties{InfoName: "private-fixture"}
	for _, format := range []string{"%v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, secret), "private-fixture") {
			t.Fatal("actuator leaks")
		}
	}
	data, e := json.Marshal(secret)
	if e != nil || string(data) != `{"redacted":true}` {
		t.Fatalf("json: %s %v", data, e)
	}
}

func TestMCPResolutionAndMalformedSourceBoundaries(t *testing.T) {
	for _, key := range []string{"mcp.enabled", "mcp.servers.fetch.command", "mcp.servers.fetch.env.TOKEN", "mcp.servers.fetch.connect-timeout", "mcp.servers.fetch.args", "mcp.servers.fetch.args[0]", "mcp.servers.fetch.auto-tools", "mcp.servers.fetch.auto-tools[0]"} {
		t.Run(key, func(t *testing.T) {
			_, e := bindMCPResolved(func(string) (string, error) { return "", errPropertyResolution }, BindingSource{Values: map[string]string{key: "private"}})
			if e == nil {
				t.Fatal("resolution error swallowed")
			}
		})
	}
	for _, key := range []string{"mcp.servers.[bad"} {
		if _, e := BindMCPProperties(BindingSource{Values: map[string]string{key: "true"}}); e == nil {
			t.Fatalf("malformed %q accepted", key)
		}
	}
	if _, e := BindMCPProperties(BindingSource{Values: map[string]string{"other..key": "ignored", "other.value": "ignored", "mcp.servers.[].command": "ignored"}}); e != nil {
		t.Fatal(e)
	}
	if _, e := BindMCPProperties(BindingSource{Values: map[string]string{"mcp.servers.fetch.auto-tools[2]": "two"}}); e == nil {
		t.Fatal("invalid list accepted")
	}
}

func TestProfileAndExpansionResourceBounds(t *testing.T) {
	for _, key := range []string{"spring.profiles.active", "spring.profiles.default", "spring.profiles.include"} {
		for _, value := range []string{"$" + "{missing}", "bad!"} {
			if _, e := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{key: value}}); e == nil {
				t.Fatal("invalid profile accepted", key)
			}
		}
	}
	for _, key := range []string{"SPRING_PROFILES_ACTIVE", "SPRING_PROFILES_DEFAULT"} {
		for _, value := range []string{"$" + "{missing}", "!invalid"} {
			if _, e := LoadBaselineProperties(PropertyOptions{DotEnv: map[string]string{key: value}}); e == nil {
				t.Fatal("invalid lazy profile accepted", key)
			}
		}
	}
	p := &Properties{}
	if canonicalPropertyKey("") {
		t.Fatal("empty key accepted")
	}
	for _, input := range []string{strings.Repeat("a", 1<<20+1), strings.Repeat("x", 1<<20) + "$" + "{key}", "$" + "{" + strings.Repeat("$"+"{", 66) + "x" + strings.Repeat("}", 66) + "}"} {
		if _, e := p.expand(input, map[string]bool{}, 0); e == nil {
			t.Fatal("expansion bound not enforced")
		}
	}
	p.sources = []BindingSource{{Values: map[string]string{"large": strings.Repeat("a", 600000)}}}
	if _, _, e := p.Get("large"); e != nil {
		t.Fatal(e)
	}
	if _, e := p.expand("$"+"{large}"+"$"+"{large}", map[string]bool{}, 0); e == nil {
		t.Fatal("combined expansion bound not enforced")
	}
}
