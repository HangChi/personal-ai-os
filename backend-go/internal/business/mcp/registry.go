package mcp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"slices"
	"strings"
	"sync"
	"time"

	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"personal-ai-os/backend-go/internal/platform/api"
)

type ServerConfig struct {
	Command   string
	Args      []string
	Env       map[string]string
	AutoTools []string
	Timeout   time.Duration
	Endpoint  string
	Headers   http.Header
}

func (ServerConfig) String() string               { return "MCPServerConfig{redacted}" }
func (v ServerConfig) GoString() string           { return v.String() }
func (ServerConfig) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

type Options struct {
	Enabled       bool
	Name, Version string
	Servers       map[string]ServerConfig
	HTTPClient    *http.Client
}

func (Options) String() string     { return "MCPOptions{redacted}" }
func (v Options) GoString() string { return v.String() }

type ServerStatus struct {
	Name          string   `json:"name"`
	Available     bool     `json:"available"`
	FailureReason *string  `json:"failureReason"`
	ToolCount     int      `json:"toolCount"`
	AutoTools     []string `json:"autoTools"`
}
type Tool struct {
	Server, Original, Name, Description, Policy string
	Schema                                      map[string]any
}
type serverSlot struct {
	op      sync.Mutex
	config  ServerConfig
	session *sdk.ClientSession
	tools   []*sdk.Tool
	status  ServerStatus
}
type Registry struct {
	mu        sync.RWMutex
	ctx       context.Context
	cancel    context.CancelFunc
	closed    bool
	enabled   bool
	servers   map[string]*serverSlot
	info      sdk.Implementation
	client    *http.Client
	transport *http.Transport
	logger    *slog.Logger
}

func (*Registry) String() string { return "MCPRegistry{redacted}" }

func OpenRegistry(ctx context.Context, options Options, logger *slog.Logger) (*Registry, error) {
	if ctx == nil || logger == nil {
		return nil, errors.New("MCP需要显式context和日志")
	}
	r := &Registry{enabled: options.Enabled, servers: map[string]*serverSlot{}, info: sdk.Implementation{Name: options.Name, Version: options.Version}, logger: logger}
	r.ctx, r.cancel = context.WithCancel(ctx)
	if !options.Enabled {
		return r, nil
	}
	for name, c := range options.Servers {
		if strings.TrimSpace(c.Endpoint) == "" && strings.TrimSpace(c.Command) == "" {
			r.cancel()
			return nil, api.NewBusinessError(api.ValidationFailed, "MCP server「"+name+"」未配置启动命令：请设置 MCP_SERVERS_"+strings.ToUpper(name)+"_COMMAND")
		}
		if c.Endpoint != "" {
			u, err := url.Parse(c.Endpoint)
			if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Hostname() == "" || u.User != nil || u.Fragment != "" || c.Command != "" {
				r.cancel()
				return nil, api.NewBusinessError(api.ValidationFailed, "MCP HTTP端点配置无效")
			}
		}
		if c.Timeout <= 0 {
			c.Timeout = 10 * time.Second
		}
		c.Args = slices.Clone(c.Args)
		c.Env = maps.Clone(c.Env)
		c.AutoTools = slices.Clone(c.AutoTools)
		slices.Sort(c.AutoTools)
		c.AutoTools = slices.Compact(c.AutoTools)
		c.Headers = c.Headers.Clone()
		r.servers[name] = &serverSlot{config: c, status: ServerStatus{Name: name, AutoTools: append([]string{}, c.AutoTools...)}}
	}
	if options.HTTPClient != nil {
		copy := *options.HTTPClient
		r.client = &copy
	} else {
		r.transport = http.DefaultTransport.(*http.Transport).Clone()
		r.client = &http.Client{Transport: r.transport}
	}
	r.client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	for _, name := range slices.Sorted(maps.Keys(r.servers)) {
		if err := ctx.Err(); err != nil {
			r.Close()
			return nil, err
		}
		_ = r.Reconnect(ctx, name)
	}
	return r, nil
}

func (r *Registry) session(name string) *sdk.ClientSession {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if slot := r.servers[name]; slot != nil {
		return slot.session
	}
	return nil
}
func (r *Registry) Statuses() []ServerStatus {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []ServerStatus{}
	for _, name := range slices.Sorted(maps.Keys(r.servers)) {
		s := r.servers[name].status
		s.AutoTools = slices.Clone(s.AutoTools)
		if s.FailureReason != nil {
			v := *s.FailureReason
			s.FailureReason = &v
		}
		out = append(out, s)
	}
	return out
}
func (r *Registry) Enabled() bool { return r.enabled }

func (r *Registry) Reconnect(parent context.Context, name string) error {
	r.mu.RLock()
	slot := r.servers[name]
	closed := r.closed
	r.mu.RUnlock()
	if closed {
		return errors.New("MCP已关闭")
	}
	if !r.enabled {
		return api.NewBusinessError(api.AINotConfigured, "MCP 未启用，无法重连")
	}
	if slot == nil {
		return api.NewBusinessError(api.ResourceNotFound, "未知的 MCP server："+name)
	}
	slot.op.Lock()
	defer slot.op.Unlock()
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return errors.New("MCP已关闭")
	}
	old := slot.session
	slot.session = nil
	slot.tools = nil
	r.mu.Unlock()
	if old != nil {
		_ = old.Close()
	}
	ctx, cancel := context.WithTimeout(parent, slot.config.Timeout)
	defer cancel()
	stop := context.AfterFunc(r.ctx, cancel)
	defer stop()
	var transport sdk.Transport
	if slot.config.Endpoint != "" {
		client := *r.client
		base := client.Transport
		if base == nil {
			base = http.DefaultTransport
		}
		client.Transport = headerTransport{base, slot.config.Headers}
		transport = &sdk.StreamableClientTransport{Endpoint: slot.config.Endpoint, HTTPClient: &client, MaxRetries: -1, DisableStandaloneSSE: true, MaxEventSize: 8 << 20}
	} else {
		cmd := exec.Command(slot.config.Command, slot.config.Args...)
		cmd.Env = childEnvironment(slot.config.Env)
		cmd.Stderr = io.Discard
		transport = &sdk.CommandTransport{Command: cmd, TerminateDuration: 200 * time.Millisecond}
	}
	client := sdk.NewClient(&r.info, &sdk.ClientOptions{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	session, err := client.Connect(ctx, transport, &sdk.ClientSessionOptions{ProtocolVersion: "2025-06-18"})
	var tools []*sdk.Tool
	if err == nil {
		seen := map[string]bool{}
		cursor := ""
		for page := 0; page < 128; page++ {
			var result *sdk.ListToolsResult
			result, err = session.ListTools(ctx, &sdk.ListToolsParams{Cursor: cursor})
			if err != nil {
				break
			}
			tools = append(tools, result.Tools...)
			if len(tools) > 4096 {
				err = errors.New("MCP工具目录过大")
				break
			}
			if result.NextCursor == "" {
				break
			}
			if seen[result.NextCursor] || page == 127 {
				err = errors.New("MCP工具分页未收敛")
				break
			}
			cursor = result.NextCursor
			seen[cursor] = true
		}
	}
	if err != nil {
		if session != nil {
			_ = session.Close()
		}
		r.mu.Lock()
		reason := "MCP server 连接失败或超时，请检查配置后重连"
		slot.status.Available = false
		slot.status.FailureReason = &reason
		slot.status.ToolCount = 0
		r.mu.Unlock()
		r.logger.Warn("MCP server不可用", "server", name)
		return nil // Availability failure is deliberately degraded, unlike invalid configuration.
	}
	r.mu.Lock()
	if r.closed || r.ctx.Err() != nil {
		r.mu.Unlock()
		_ = session.Close()
		return errors.New("MCP已关闭")
	}
	slot.session = session
	slot.tools = tools
	slot.status.Available = true
	slot.status.FailureReason = nil
	slot.status.ToolCount = len(tools)
	r.mu.Unlock()
	return nil
}

func (r *Registry) markUnavailable(name string, failed *sdk.ClientSession) {
	r.mu.Lock()
	slot := r.servers[name]
	if slot == nil || slot.session != failed || failed == nil {
		r.mu.Unlock()
		return
	}
	slot.session = nil
	slot.tools = nil
	slot.status.Available = false
	slot.status.ToolCount = 0
	reason := "MCP server 连接失败，请重连后重试"
	slot.status.FailureReason = &reason
	r.mu.Unlock()
	_ = failed.Close()
}

func (r *Registry) Call(parent context.Context, server, tool string, args map[string]any) (*sdk.CallToolResult, error) {
	return r.CallWithOperationID(parent, server, tool, args, "")
}

// CallWithOperationID sends optional application metadata, without altering tool arguments.
// A remote server must explicitly support the key to provide end-to-end idempotency.
func (r *Registry) CallWithOperationID(parent context.Context, server, tool string, args map[string]any, operationID string) (*sdk.CallToolResult, error) {
	r.mu.RLock()
	slot := r.servers[server]
	var session *sdk.ClientSession
	var timeout time.Duration
	if slot != nil && !r.closed {
		session = slot.session
		timeout = slot.config.Timeout
	}
	r.mu.RUnlock()
	if session == nil {
		return nil, api.NewBusinessError(api.ResourceNotFound, "MCP server 不可用："+server)
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	stop := context.AfterFunc(r.ctx, cancel)
	defer stop()
	var meta sdk.Meta
	if operationID != "" {
		meta = sdk.Meta{IdempotencyMetaKey: operationID}
	}
	result, err := session.CallTool(ctx, &sdk.CallToolParams{Name: tool, Arguments: args, Meta: meta})
	if err != nil {
		if parent.Err() != nil {
			return nil, parent.Err()
		}
		// A JSON-RPC rejection proves the server answered; keep the connection usable.
		if protocol := protocolCallError(err); protocol != nil {
			return nil, protocol
		}
		r.markUnavailable(server, session)
		return nil, errors.New("MCP server 连接失败，请重连后重试")
	}
	raw, err := json.Marshal(result)
	if err != nil || len(raw) > 8<<20 {
		return nil, errors.New("MCP工具响应过大或无效")
	}
	return result, nil
}

func (r *Registry) Catalog() []Tool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []Tool{}
	for _, name := range slices.Sorted(maps.Keys(r.servers)) {
		slot := r.servers[name]
		for _, tool := range slot.tools {
			if tool == nil {
				continue
			}
			schema, ok := NormalizeSchema(tool.InputSchema)
			if !ok {
				continue
			}
			out = append(out, Tool{Server: name, Original: tool.Name, Name: PrefixedName(name, tool.Name), Description: tool.Description, Policy: Policy(tool.Name, slot.config.AutoTools), Schema: schema})
		}
	}
	return out
}

func (r *Registry) Close() {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.closed = true
	r.cancel()
	sessions := []*sdk.ClientSession{}
	for _, slot := range r.servers {
		if slot.session != nil {
			sessions = append(sessions, slot.session)
			slot.session = nil
		}
		slot.status.Available = false
		slot.status.ToolCount = 0
		slot.tools = nil
	}
	r.mu.Unlock()
	for _, s := range sessions {
		_ = s.Close()
	}
	// An in-progress reconnect owns its slot until its cancelled handshake has closed.
	for _, slot := range r.servers {
		slot.op.Lock()
		slot.op.Unlock()
	}
	if r.transport != nil {
		r.transport.CloseIdleConnections()
	}
}

type headerTransport struct {
	base    http.RoundTripper
	headers http.Header
}

func (t headerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	r := req.Clone(req.Context())
	r.Header = req.Header.Clone()
	for k, v := range t.headers {
		r.Header[k] = slices.Clone(v)
	}
	return t.base.RoundTrip(r)
}
func childEnvironment(extra map[string]string) []string {
	// Same bounded parent environment as SDK stdio defaults; application secrets are not inherited.
	out := map[string]string{}
	for _, key := range []string{"HOME", "PATH", "SYSTEMROOT", "TEMP", "TMP", "USER", "LANG"} {
		if value, ok := os.LookupEnv(key); ok {
			out[key] = value
		}
	}
	maps.Copy(out, extra)
	result := []string{}
	for _, key := range slices.Sorted(maps.Keys(out)) {
		result = append(result, fmt.Sprintf("%s=%s", key, out[key]))
	}
	return result
}
