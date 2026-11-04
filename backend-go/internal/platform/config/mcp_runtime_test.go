package config

import "testing"

func TestP7ResolvedMCPPrecedenceAndDisabled(t *testing.T) {
	defaults, _ := LoadBaselineProperties(PropertyOptions{})
	if m, e := defaults.MCP(); e != nil || m.Enabled() {
		t.Fatal("default MCP must stay disabled", e)
	}
	p, e := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"mcp.enabled": "true", "mcp.servers.demo.command": "${BIN}", "BIN": "safe", "mcp.servers.demo.args": "${ARG},two", "ARG": "one", "mcp.servers.demo.connect-timeout": "${TIME:1s}"}, DotEnv: map[string]string{"MCP_SERVERS_DEMO_COMMAND": "${MISSING}", "MCP_SERVERS_DEMO_ARGS": "${MISSING}"}})
	if e != nil {
		t.Fatal(e)
	}
	m, e := p.MCP()
	if e != nil {
		t.Fatal(e)
	}
	s, _ := m.Server("demo")
	if *s.Command() != "safe" || len(s.Args()) != 2 || s.Args()[0] != "one" {
		t.Fatal("source precedence lost")
	}
	disabled, _ := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"mcp.enabled": "false", "mcp.servers.demo.command": "${MISSING}"}})
	if _, e = disabled.MCP(); e != nil {
		t.Fatal("disabled credentials resolved", e)
	}
	h, _ := LoadBaselineProperties(PropertyOptions{System: map[string]string{"MCP_ENABLED": "true", "MCP_HTTPSERVERS_REMOTE_ENDPOINT": "http://localhost:1/mcp", "MCP_HTTPSERVERS_REMOTE_HEADERS_AUTHORIZATION": "fixture", "MCP_HTTPSERVERS_REMOTE_AUTOTOOLS": "read"}})
	r, e := h.MCPHTTP()
	if e != nil {
		t.Fatal(e)
	}
	s, ok := r.Server("remote")
	if !ok || *s.Command() != "http://localhost:1/mcp" || s.Environment()["authorization"] != "fixture" || len(s.AutoTools()) != 1 {
		t.Fatal("HTTP binding lost")
	}
}
