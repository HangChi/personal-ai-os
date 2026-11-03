package model

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Protocol string

const (
	OpenAI    Protocol = "OPENAI"
	Anthropic Protocol = "ANTHROPIC"
	Ollama    Protocol = "OLLAMA"
)

func ParseProtocol(raw string) Protocol {
	switch Protocol(strings.ToUpper(strip(raw))) {
	case Anthropic:
		return Anthropic
	case Ollama:
		return Ollama
	default:
		return OpenAI
	}
}
func httpURL(raw string) bool {
	return strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://")
}
func endpoint(base, path string) string {
	base = strings.TrimRight(strip(base), "/")
	if !strings.HasSuffix(base, "/v1") {
		base += "/v1"
	}
	return base + path
}
func rootEndpoint(base string) string {
	return strings.TrimSuffix(strings.TrimRight(strip(base), "/"), "/v1")
}

// Transport 由应用显式注入；无环境变量扫描、SDK重试或自动重定向。
func NewTransport() *http.Transport {
	return &http.Transport{Proxy: http.ProxyFromEnvironment, DialContext: (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext, TLSHandshakeTimeout: 10 * time.Second, MaxIdleConns: 100, MaxIdleConnsPerHost: 10, IdleConnTimeout: 90 * time.Second, ForceAttemptHTTP2: true}
}
func httpClient(transport http.RoundTripper) *http.Client {
	return &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

type Image struct {
	MIME string
	Data []byte
	URL  string
}
type Tool struct {
	Name, Description string
	Parameters        map[string]any
}
type ToolCall struct {
	ID       string       `json:"id"`
	Type     string       `json:"type"`
	Function ToolFunction `json:"function"`
	Index    int          `json:"index,omitempty"`
}
type ToolFunction struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}
type Message struct {
	Role, Content, ToolCallID, Name string
	Images                          []Image
	ToolCalls                       []ToolCall
}
type ChatRequest struct {
	Messages    []Message
	Tools       []Tool
	ExtraBody   map[string]any
	MaxTokens   *int
	Temperature *Temperature
}
type Usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}
type ChatResult struct {
	ID, Model, Content, Thinking, FinishReason string
	ToolCalls                                  []ToolCall
	Usage                                      Usage
}
type ChatChunk struct {
	Content, Thinking, FinishReason string
	ToolCalls                       []ToolCall
	Usage                           *Usage
}

// 上游正文只在内部供窗口证据提取，Error/JSON/格式化都不包含密钥或原始响应。
type UpstreamError struct {
	Status int
	body   string
}

func (e *UpstreamError) Error() string {
	return fmt.Sprintf("模型端点请求失败（HTTP %d）", e.Status)
}
func (e *UpstreamError) GoString() string { return e.Error() }
func (e *UpstreamError) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Status int `json:"status"`
	}{e.Status})
}

type ChatClient struct {
	protocol Protocol
	resolved Resolved
	http     *http.Client
	timeout  time.Duration
	extra    map[string]any
}

func (*ChatClient) String() string               { return "ChatClient{redacted}" }
func (c *ChatClient) GoString() string           { return c.String() }
func (*ChatClient) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func NewChatClient(resolved Resolved, protocol Protocol, transport http.RoundTripper) (*ChatClient, error) {
	u, err := url.Parse(resolved.BaseURL)
	if err != nil || !httpURL(resolved.BaseURL) || u.Host == "" || strings.ContainsAny(resolved.APIKey(), "\r\n") || strip(resolved.ChatModel) == "" {
		return nil, errors.New("端点、凭据或模型名格式不合法")
	}
	if protocol != Ollama && !resolved.HasAPIKey() {
		return nil, errors.New("模型凭据为空")
	}
	return &ChatClient{protocol: protocol, resolved: resolved, http: httpClient(transport), timeout: 120 * time.Second}, nil
}
func (c *ChatClient) Provider() string { return string(c.protocol) }
func (c *ChatClient) Name() string     { return c.resolved.ChatModel }
func (c *ChatClient) payload(r ChatRequest, stream bool) (map[string]any, error) {
	messages := make([]any, 0, len(r.Messages))
	for _, m := range r.Messages {
		v := map[string]any{"role": m.Role, "content": m.Content}
		if m.ToolCallID != "" {
			v["tool_call_id"] = m.ToolCallID
		}
		if m.Name != "" {
			v["name"] = m.Name
		}
		if len(m.ToolCalls) > 0 {
			v["tool_calls"] = m.ToolCalls
		}
		if len(m.Images) > 0 {
			if c.protocol == Ollama {
				images := []string{}
				for _, im := range m.Images {
					if im.URL != "" {
						return nil, errors.New("Ollama图片必须显式提供字节")
					}
					images = append(images, base64.StdEncoding.EncodeToString(im.Data))
				}
				v["images"] = images
			} else {
				parts := []any{map[string]any{"type": "text", "text": m.Content}}
				for _, im := range m.Images {
					address := im.URL
					if address == "" {
						address = "data:" + im.MIME + ";base64," + base64.StdEncoding.EncodeToString(im.Data)
					}
					parts = append(parts, map[string]any{"type": "image_url", "image_url": map[string]any{"url": address}})
				}
				v["content"] = parts
			}
		}
		if c.protocol == Ollama && len(m.ToolCalls) > 0 {
			calls := []any{}
			for _, tc := range m.ToolCalls {
				var args any
				if err := json.Unmarshal([]byte(tc.Function.Arguments), &args); err != nil {
					return nil, errors.New("工具参数不是合法JSON")
				}
				calls = append(calls, map[string]any{"function": map[string]any{"name": tc.Function.Name, "arguments": args}})
			}
			v["tool_calls"] = calls
		}
		messages = append(messages, v)
	}
	body := map[string]any{"model": c.Name(), "messages": messages, "stream": stream}
	temperature := c.resolved.Temperature
	if r.Temperature != nil {
		temperature = r.Temperature
	}
	if c.protocol == Ollama {
		opts := map[string]any{}
		if temperature != nil {
			opts["temperature"] = temperature
		}
		if r.MaxTokens != nil {
			opts["num_predict"] = *r.MaxTokens
		}
		body["options"] = opts
	} else {
		if temperature != nil {
			body["temperature"] = temperature
		}
		if r.MaxTokens != nil {
			body["max_tokens"] = *r.MaxTokens
		}
		if stream {
			body["stream_options"] = map[string]any{"include_usage": true}
		}
	}
	if len(r.Tools) > 0 {
		tools := []any{}
		for _, t := range r.Tools {
			tools = append(tools, map[string]any{"type": "function", "function": map[string]any{"name": t.Name, "description": t.Description, "parameters": t.Parameters}})
		}
		body["tools"] = tools
	}
	for k, v := range c.extra {
		body[k] = v
	}
	for k, v := range r.ExtraBody {
		body[k] = v
	}
	return body, nil
}
func (c *ChatClient) invoke(ctx context.Context, r ChatRequest, stream bool) (*http.Response, context.CancelFunc, error) {
	body, err := c.payload(r, stream)
	if err != nil {
		return nil, nil, err
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, nil, errors.New("模型请求无法序列化")
	}
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	address := endpoint(c.resolved.BaseURL, "/chat/completions")
	if c.protocol == Ollama {
		address = rootEndpoint(c.resolved.BaseURL) + "/api/chat"
	}
	req, err := http.NewRequestWithContext(ctx, "POST", address, bytes.NewReader(raw))
	if err != nil {
		cancel()
		return nil, nil, errors.New("模型端点格式不合法")
	}
	req.GetBody = nil // POST 永不通过可重放body触发传输重试。
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if c.resolved.HasAPIKey() {
		req.Header.Set("Authorization", "Bearer "+c.resolved.APIKey())
	}
	resp, err := c.http.Do(req)
	if err != nil {
		cause := ctx.Err()
		cancel()
		if cause != nil {
			return nil, nil, cause
		}
		return nil, nil, errors.New("模型端点网络连接失败")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		cancel()
		return nil, nil, &UpstreamError{resp.StatusCode, string(data)}
	}
	return resp, cancel, nil
}
func (c *ChatClient) Call(ctx context.Context, r ChatRequest) (ChatResult, error) {
	resp, cancel, err := c.invoke(ctx, r, false)
	if err != nil {
		return ChatResult{}, err
	}
	defer cancel()
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return ChatResult{}, err
	}
	chunk, id, model, err := decodeChat(data, c.protocol, false)
	if err != nil {
		return ChatResult{}, err
	}
	out := ChatResult{ID: id, Model: model}
	mergeChunk(&out, chunk)
	return out, nil
}
func mergeChunk(out *ChatResult, ch ChatChunk) {
	out.Content += ch.Content
	out.Thinking += ch.Thinking
	if ch.FinishReason != "" {
		out.FinishReason = ch.FinishReason
	}
	if ch.Usage != nil {
		out.Usage = *ch.Usage
	}
	for _, tc := range ch.ToolCalls {
		for len(out.ToolCalls) <= tc.Index {
			out.ToolCalls = append(out.ToolCalls, ToolCall{})
		}
		v := &out.ToolCalls[tc.Index]
		v.ID += tc.ID
		v.Type += tc.Type
		v.Function.Name += tc.Function.Name
		v.Function.Arguments += tc.Function.Arguments
	}
}

// Stream 消费上游SSE/NDJSON；HTTP的应用级SSE事件和Agent循环留给P4。
func (c *ChatClient) Stream(ctx context.Context, r ChatRequest, receive func(ChatChunk) error) (ChatResult, error) {
	resp, cancel, err := c.invoke(ctx, r, true)
	if err != nil {
		return ChatResult{}, err
	}
	defer cancel()
	defer resp.Body.Close()
	scan := bufio.NewScanner(resp.Body)
	scan.Buffer(make([]byte, 4096), 32<<20)
	out := ChatResult{}
	var data strings.Builder
	finished := false
	consume := func(raw string) error {
		if raw == "[DONE]" {
			finished = true
			return nil
		}
		ch, id, model, e := decodeChat([]byte(raw), c.protocol, true)
		if e != nil {
			return e
		}
		if id != "" {
			out.ID = id
		}
		if model != "" {
			out.Model = model
		}
		mergeChunk(&out, ch)
		if c.protocol == Ollama && ch.FinishReason != "" {
			finished = true
		}
		if receive != nil {
			return receive(ch)
		}
		return nil
	}
	for scan.Scan() {
		if err = ctx.Err(); err != nil {
			return out, err
		}
		line := scan.Text()
		if c.protocol == Ollama {
			if err = consume(line); err != nil {
				return out, err
			}
			continue
		}
		if line == "" {
			if data.Len() > 0 {
				err = consume(strings.TrimSuffix(data.String(), "\n"))
				data.Reset()
				if err != nil {
					return out, err
				}
				if finished {
					break
				}
			}
			continue
		}
		if strings.HasPrefix(line, "data:") {
			data.WriteString(strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
			data.WriteByte('\n')
		}
	}
	if err = scan.Err(); err != nil {
		return out, err
	}
	if data.Len() > 0 && !finished {
		if err = consume(strings.TrimSuffix(data.String(), "\n")); err != nil {
			return out, err
		}
	}
	if !finished {
		return out, errors.New("模型流意外结束")
	}
	return out, nil
}
func decodeChat(data []byte, p Protocol, delta bool) (ChatChunk, string, string, error) {
	var raw struct {
		ID      string          `json:"id"`
		Model   string          `json:"model"`
		Error   json.RawMessage `json:"error"`
		Usage   *Usage          `json:"usage"`
		Choices []struct {
			Message      json.RawMessage `json:"message"`
			Delta        json.RawMessage `json:"delta"`
			FinishReason string          `json:"finish_reason"`
		} `json:"choices"`
		Message    json.RawMessage `json:"message"`
		Done       bool            `json:"done"`
		DoneReason string          `json:"done_reason"`
		Prompt     int             `json:"prompt_eval_count"`
		Completion int             `json:"eval_count"`
	}
	if err := json.Unmarshal(data, &raw); err != nil || len(raw.Error) > 0 && string(raw.Error) != "null" {
		return ChatChunk{}, "", "", errors.New("模型响应格式不合法或包含错误")
	}
	var msg json.RawMessage
	ch := ChatChunk{Usage: raw.Usage}
	if p == Ollama {
		msg = raw.Message
		if raw.Done {
			ch.FinishReason = raw.DoneReason
			if ch.FinishReason == "" {
				ch.FinishReason = "stop"
			}
			ch.Usage = &Usage{raw.Prompt, raw.Completion, raw.Prompt + raw.Completion}
		}
	} else if len(raw.Choices) > 0 {
		msg = raw.Choices[0].Message
		if delta {
			msg = raw.Choices[0].Delta
		}
		ch.FinishReason = raw.Choices[0].FinishReason
	} else if raw.Usage == nil {
		return ch, "", "", errors.New("模型响应缺少choices")
	}
	var m struct {
		Content   string `json:"content"`
		Thinking  string `json:"thinking"`
		Reasoning string `json:"reasoning_content"`
		ToolCalls []struct {
			ID       string `json:"id"`
			Type     string `json:"type"`
			Index    *int   `json:"index"`
			Function struct {
				Name      string          `json:"name"`
				Arguments json.RawMessage `json:"arguments"`
			} `json:"function"`
		} `json:"tool_calls"`
	}
	if len(msg) > 0 {
		if err := json.Unmarshal(msg, &m); err != nil {
			return ch, "", "", errors.New("模型消息格式不合法")
		}
	}
	ch.Content = m.Content
	ch.Thinking = m.Thinking + m.Reasoning
	for i, tc := range m.ToolCalls {
		index := i
		if tc.Index != nil {
			index = *tc.Index
		}
		if index < 0 || index > 1024 {
			return ch, "", "", errors.New("工具调用索引非法")
		}
		args := ""
		if len(tc.Function.Arguments) > 0 {
			if p == Ollama {
				args = string(tc.Function.Arguments)
			} else if err := json.Unmarshal(tc.Function.Arguments, &args); err != nil {
				return ch, "", "", errors.New("工具参数格式不合法")
			}
		}
		ch.ToolCalls = append(ch.ToolCalls, ToolCall{tc.ID, tc.Type, ToolFunction{tc.Function.Name, args}, index})
	}
	return ch, raw.ID, raw.Model, nil
}
