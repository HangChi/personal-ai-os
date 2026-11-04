package config

import (
	"maps"
	"strings"
)

// MCP preserves source precedence and collection replacement before resolving selected values.
func (p *Properties) MCP() (MCPProperties, error) { return p.mcpRuntime(false) }

// MCPHTTP reuses collection binding for the additive mcp.http-servers namespace.
// Command carries endpoint and Environment carries HTTP headers in this transport-only view.
func (p *Properties) MCPHTTP() (MCPProperties, error) { return p.mcpRuntime(true) }
func (p *Properties) mcpRuntime(http bool) (MCPProperties, error) {
	enabled, found, err := p.Get("mcp.enabled")
	if err != nil {
		return MCPProperties{}, err
	}
	if !found {
		return MCPProperties{servers: map[string]MCPServerConfig{}}, nil
	}
	active, err := bindBoolean(map[string]string{"mcp.enabled": enabled}, "mcp.enabled", false)
	if err != nil || !active {
		return MCPProperties{servers: map[string]MCPServerConfig{}}, err
	}
	sources := []BindingSource{}
	for _, s := range p.sources {
		values := map[string]string{}
		for k, v := range s.Values {
			key := k
			if s.Environment {
				key = environmentBindingKey(key)
			}
			if http {
				if !strings.HasPrefix(key, "mcp.http-servers.") && !strings.HasPrefix(key, "mcp.httpservers.") {
					continue
				}
				key = strings.Replace(key, "mcp.http-servers.", "mcp.servers.", 1)
				key = strings.Replace(key, "mcp.httpservers.", "mcp.servers.", 1)
				key = strings.Replace(key, ".endpoint", ".command", 1)
				key = strings.Replace(key, ".headers", ".env", 1)
			} else if key != "mcp.enabled" && !strings.HasPrefix(key, "mcp.servers") {
				continue
			}
			if s.Environment && !http {
				values[k] = v
			} else {
				values[key] = v
			}
		}
		sources = append(sources, BindingSource{Environment: s.Environment && !http, Values: maps.Clone(values)})
	}
	sources = append([]BindingSource{{Values: map[string]string{"mcp.enabled": "true"}}}, sources...)
	return bindMCPResolved(func(v string) (string, error) { return p.expand(v, map[string]bool{}, 0) }, sources...)
}
