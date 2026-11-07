package mcp

import (
	"personal-ai-os/backend-go/internal/platform/config"
	"testing"
)

func configBoundary(t *testing.T, values map[string]string) *config.Properties {
	t.Helper()
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func TestPersonalConfigurationRejectsUnresolvedAndUnsafeEndpoints(t *testing.T) {
	for _, key := range []string{"name", "version", "streamable-http.mcp-endpoint", "enabled"} {
		if _, e := PersonalOptionsFrom(configBoundary(t, map[string]string{"spring.ai.mcp.server." + key: "$" + "{missing}"})); e == nil {
			t.Fatal(key)
		}
	}
	for _, endpoint := range []string{"/", "relative", "/api/v1/tool", "/actuator/health", "/a/../b", "/x?y", "/x%20y", "/x;y"} {
		if _, e := PersonalOptionsFrom(configBoundary(t, map[string]string{"spring.ai.mcp.server.streamable-http.mcp-endpoint": endpoint})); e == nil {
			t.Fatal(endpoint)
		}
	}
	for _, v := range []string{"bad", "true", "false"} {
		o, e := PersonalOptionsFrom(configBoundary(t, map[string]string{"spring.ai.mcp.server.enabled": v, "spring.ai.mcp.server.name": "fixture", "spring.ai.mcp.server.version": "2", "spring.ai.mcp.server.streamable-http.mcp-endpoint": "/tools"}))
		if (e != nil) != (v == "bad") {
			t.Fatal(v, e)
		}
		if e == nil && (o.Name != "fixture" || o.Version != "2" || o.Endpoint != "/tools" || o.Enabled != (v == "true")) {
			t.Fatal(o)
		}
	}
}
func TestClientConfigurationErrorsDoNotEnablePartialCatalog(t *testing.T) {
	for _, values := range []map[string]string{
		{"mcp.enabled": "invalid"},
		{"mcp.enabled": "true", "mcp.http-servers.remote.endpoint": "$" + "{missing}"},
		{"mcp.enabled": "true", "mcp.servers.same.command": "synthetic", "mcp.http-servers.same.endpoint": "https://example.test"},
	} {
		if _, e := OptionsFrom(configBoundary(t, values)); e == nil {
			t.Fatal(values)
		}
	}
	p := configBoundary(t, map[string]string{"mcp.enabled": "true", "mcp.http-servers.remote.endpoint": "https://example.test", "mcp.http-servers.remote.headers.X-Test": "synthetic"})
	o, e := OptionsFrom(p)
	if e != nil || o.Servers["remote"].Endpoint != "https://example.test" {
		t.Fatal(o, e)
	}
}
