package mcp

import (
	"errors"
	"net/http"
	"path"
	"personal-ai-os/backend-go/internal/platform/config"
	"strconv"
	"strings"
)

func OptionsFrom(p *config.Properties) (Options, error) {
	stdio, err := p.MCP()
	if err != nil {
		return Options{}, err
	}
	out := Options{Enabled: stdio.Enabled(), Name: "personal-ai-os", Version: "0.1.0", Servers: map[string]ServerConfig{}}
	if !out.Enabled {
		return out, nil
	}
	for _, name := range stdio.ServerNames() {
		s, _ := stdio.Server(name)
		c := ServerConfig{Args: s.Args(), Env: s.Environment(), AutoTools: s.AutoTools(), Timeout: s.ConnectTimeout()}
		if s.Command() != nil {
			c.Command = *s.Command()
		}
		out.Servers[name] = c
	}
	remote, err := p.MCPHTTP()
	if err != nil {
		return Options{}, err
	}
	for _, name := range remote.ServerNames() {
		if _, ok := out.Servers[name]; ok {
			return Options{}, errors.New("MCP stdio/HTTP server 名称重复")
		}
		s, _ := remote.Server(name)
		c := ServerConfig{AutoTools: s.AutoTools(), Timeout: s.ConnectTimeout(), Headers: http.Header{}}
		if s.Command() != nil {
			c.Endpoint = *s.Command()
		}
		for k, v := range s.Environment() {
			c.Headers.Set(k, v)
		}
		out.Servers[name] = c
	}
	return out, nil
}

type PersonalOptions struct {
	Enabled                 bool
	Name, Version, Endpoint string
}

func PersonalOptionsFrom(p *config.Properties) (PersonalOptions, error) {
	o := PersonalOptions{Name: "personal-ai-os", Version: "0.1.0", Endpoint: "/mcp"}
	for _, s := range []struct {
		key string
		dst *string
	}{{"name", &o.Name}, {"version", &o.Version}, {"streamable-http.mcp-endpoint", &o.Endpoint}} {
		v, ok, e := p.Get("spring.ai.mcp.server." + s.key)
		if e != nil {
			return o, e
		}
		if ok {
			*s.dst = v
		}
	}
	v, ok, e := p.Get("spring.ai.mcp.server.enabled")
	if e != nil {
		return o, e
	}
	if ok {
		o.Enabled, e = strconv.ParseBool(v)
		if e != nil {
			return o, e
		}
	}
	if o.Endpoint == "/" || !strings.HasPrefix(o.Endpoint, "/") || path.Clean(o.Endpoint) != o.Endpoint || strings.ContainsAny(o.Endpoint, "?#;\\%") || strings.HasPrefix(o.Endpoint, "/api/") || strings.HasPrefix(o.Endpoint, "/actuator") {
		return o, errors.New("MCP 服务端点无效")
	}
	return o, nil
}
