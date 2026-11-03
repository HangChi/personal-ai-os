package model

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Probe struct {
	http                                     *http.Client
	listTimeout, detailTimeout, detailBudget time.Duration
}

func NewProbe(transport http.RoundTripper) *Probe {
	return &Probe{httpClient(transport), 10 * time.Second, 2500 * time.Millisecond, 6 * time.Second}
}

type ModelListResult struct {
	OK             bool           `json:"ok"`
	Models         []string       `json:"models"`
	LatencyMS      int64          `json:"latencyMs"`
	Error          *string        `json:"error"`
	Kind           *string        `json:"kind"`
	ContextWindows map[string]int `json:"contextWindows"`
}
type WindowProbeResult struct {
	OK                    bool    `json:"ok"`
	Window                *int    `json:"window"`
	Kind                  *string `json:"kind"`
	Error                 *string `json:"error"`
	Source                *string `json:"source"`
	SourceURL             *string `json:"sourceUrl"`
	DetailKind            *string `json:"detailKind"`
	HTTPStatus            *int    `json:"httpStatus"`
	ReportedDefaultWindow *int    `json:"reportedDefaultWindow"`
	Message               *string `json:"message"`
}
type ProbeTarget struct {
	Protocol        Protocol
	BaseURL, APIKey string
}

func (ProbeTarget) String() string               { return "ProbeTarget{redacted}" }
func (p ProbeTarget) GoString() string           { return p.String() }
func (ProbeTarget) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func listURL(t ProbeTarget) string {
	if t.Protocol == Ollama {
		return rootEndpoint(t.BaseURL) + "/api/tags"
	}
	return endpoint(t.BaseURL, "/models")
}
func authorize(req *http.Request, t ProbeTarget) {
	req.Header.Set("Accept", "application/json")
	key := strip(t.APIKey)
	if t.Protocol == Anthropic {
		req.Header.Set("x-api-key", key)
		req.Header.Set("anthropic-version", "2023-06-01")
	} else if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
}
func (p *Probe) fetch(ctx context.Context, t ProbeTarget, address, method string, body []byte, timeout time.Duration) (int, []byte, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, address, bytes.NewReader(body))
	if err != nil {
		return 0, nil, errors.New("端点地址格式不合法")
	}
	authorize(req, t)
	if method == "POST" {
		req.Header.Set("Content-Type", "application/json")
		req.GetBody = nil
	}
	resp, err := p.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return 0, nil, ctx.Err()
		}
		return 0, nil, errors.New("网络连接失败")
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil && ctx.Err() != nil {
		err = ctx.Err()
	}
	return resp.StatusCode, data, err
}
func listFailure(message, kind string) ModelListResult {
	return ModelListResult{Models: []string{}, Error: &message, Kind: &kind, ContextWindows: map[string]int{}}
}
func (p *Probe) Models(ctx context.Context, t ProbeTarget, preferred []string) ModelListResult {
	if strip(t.BaseURL) == "" {
		return listFailure("请先填写端点地址", "ERROR")
	}
	if !httpURL(strip(t.BaseURL)) {
		return listFailure("端点地址必须以 http:// 或 https:// 开头", "ERROR")
	}
	if t.Protocol != Ollama && strip(t.APIKey) == "" {
		return listFailure("请先填写 API Key", "ERROR")
	}
	start := time.Now()
	status, body, err := p.fetch(ctx, t, listURL(t), "GET", nil, p.listTimeout)
	if err != nil {
		reason := "网络连接失败"
		if errors.Is(err, context.DeadlineExceeded) {
			reason = "请求超时（超过 10 秒），请检查端点地址与网络"
		}
		if errors.Is(err, context.Canceled) {
			reason = "探测被中断"
		}
		return listFailure(reason, "ERROR")
	}
	latency := time.Since(start).Milliseconds()
	if status < 200 || status >= 300 {
		kind := "ERROR"
		reason := fmt.Sprintf("请求失败（HTTP %d）", status)
		switch status {
		case 401:
			reason = "认证失败（401）：API Key 无效，或该端点不使用这种鉴权方式"
		case 403:
			reason = "无权限（403）：该密钥不能列出模型"
		case 404:
			parsed, _ := url.Parse(listURL(t))
			reason = "接口不存在（404）：" + parsed.Path + " 在该端点上不存在，可能它不提供模型列表接口"
		case 429:
			reason = "被限流（429）：请求过于频繁"
		}
		if status == 404 || status == 405 {
			kind = "UNSUPPORTED"
		}
		return listFailure(withBody(reason, body, t.APIKey), kind)
	}
	root, err := parseNode(body)
	if err != nil {
		return listFailure("响应不是合法 JSON（该端点可能返回了 HTML 页面）："+safeBody(body, t.APIKey), "UNSUPPORTED")
	}
	if !root.isObject && !root.isArray && root.scalar == nil {
		return listFailure("响应为空", "ERROR")
	}
	array, field := root.at("data"), "id"
	if t.Protocol == Ollama {
		array, field = root.at("models"), "name"
		if len(idsOf(array, field)) == 0 {
			array, field = root.at("data"), "id"
		}
	} else if len(idsOf(array, field)) == 0 {
		if root.isArray {
			array = root
		} else {
			array = root.at("models")
		}
	}
	ids := idsOf(array, field)
	if len(ids) == 0 {
		return listFailure("接口有响应但没有解析到任何模型名，可能该端点不提供模型列表。可以手动填写模型名。", "UNSUPPORTED")
	}
	models := []string{}
	seen := map[string]bool{}
	for _, id := range ids {
		if strip(id) != "" && !seen[id] {
			seen[id] = true
			models = append(models, id)
		}
	}
	sort.SliceStable(models, func(i, j int) bool { return strings.ToLower(models[i]) < strings.ToLower(models[j]) })
	if len(models) > 500 {
		models = models[:500]
	}
	windows := map[string]int{}
	keep := map[string]bool{}
	for _, name := range models {
		keep[name] = true
	}
	for _, n := range array.array {
		name := n.at(field).text()
		if keep[name] {
			if w := metadata(n, status).window; w != nil {
				windows[name] = *w
			}
		}
	}
	missing := []string{}
	for _, name := range models {
		if _, ok := windows[name]; !ok {
			missing = append(missing, name)
		}
	}
	for name, w := range p.details(ctx, t, orderedTargets(missing, preferred)) {
		windows[name] = w
	}
	return ModelListResult{OK: true, Models: models, LatencyMS: latency, ContextWindows: windows}
}
func idsOf(n node, field string) []string {
	ids := []string{}
	for _, v := range n.array {
		if s, ok := v.at(field).scalar.(string); ok {
			ids = append(ids, s)
		}
	}
	return ids
}
func orderedTargets(models, preferred []string) []string {
	available := map[string]bool{}
	for _, m := range models {
		available[m] = true
	}
	out := []string{}
	seen := map[string]bool{}
	for _, m := range append(append([]string{}, preferred...), models...) {
		if len(out) == 12 {
			break
		}
		if available[m] && !seen[m] {
			seen[m] = true
			out = append(out, m)
		}
	}
	return out
}
func (p *Probe) details(ctx context.Context, t ProbeTarget, targets []string) map[string]int {
	ctx, cancel := context.WithTimeout(ctx, p.detailBudget)
	defer cancel()
	found := map[string]int{}
	var mu sync.Mutex
	var cursor atomic.Int64
	var stopped atomic.Bool
	var wg sync.WaitGroup
	for i := 0; i < min(4, len(targets)); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				index := int(cursor.Add(1) - 1)
				if index >= len(targets) || ctx.Err() != nil || stopped.Load() {
					return
				}
				o := p.detail(ctx, t, targets[index])
				if o.kind == "RATE_LIMITED" {
					stopped.Store(true)
					return
				}
				if o.window != nil {
					mu.Lock()
					found[targets[index]] = *o.window
					mu.Unlock()
				}
			}
		}()
	}
	wg.Wait()
	return found
}

type detailOutcome struct {
	window                         *int
	kind                           string
	status, defaultWindow, context *int
}

func failedDetail(kind string, status int) detailOutcome {
	var p *int
	if status != 0 {
		p = &status
	}
	return detailOutcome{kind: kind, status: p}
}
func httpFailure(status int) string {
	switch status {
	case 401:
		return "AUTH_FAILED"
	case 403:
		return "FORBIDDEN"
	case 404, 405:
		return "NOT_FOUND"
	case 429:
		return "RATE_LIMITED"
	default:
		return "HTTP_ERROR"
	}
}
func metadata(n node, status int) detailOutcome {
	context, input := declared(n, false), declared(n, true)
	if input != nil && (context == nil || *input < *context) {
		return detailOutcome{input, "INPUT_LIMIT", intPointer(200), nil, context}
	}
	if context != nil {
		return detailOutcome{context, "FOUND", intPointer(200), nil, context}
	}
	o := failedDetail("NO_METADATA", status)
	if derived(n.at("extra_info")) {
		o.kind = "DERIVED_DEFAULT"
		o.defaultWindow = windowNumber(n.at("extra_info").at("default_envs").at("max_input_tokens"))
	}
	return o
}
func intPointer(v int) *int { return &v }
func (p *Probe) detail(ctx context.Context, t ProbeTarget, name string) detailOutcome {
	parts := strings.Split(name, "/")
	for i := range parts {
		parts[i] = strings.ReplaceAll(url.QueryEscape(parts[i]), "~", "%7E")
	}
	address, method, body := endpoint(t.BaseURL, "/models/"+strings.Join(parts, "/")), "GET", []byte(nil)
	if t.Protocol == Ollama {
		address, method = rootEndpoint(t.BaseURL)+"/api/show", "POST"
		body, _ = json.Marshal(map[string]string{"model": name})
	}
	return p.outcome(ctx, t, address, method, body, "")
}
func (p *Probe) outcome(ctx context.Context, t ProbeTarget, address, method string, body []byte, find string) detailOutcome {
	status, data, err := p.fetch(ctx, t, address, method, body, p.detailTimeout)
	if err != nil {
		kind := "NETWORK_ERROR"
		if errors.Is(err, context.DeadlineExceeded) {
			kind = "TIMEOUT"
		}
		return failedDetail(kind, status)
	}
	if status < 200 || status >= 300 {
		return failedDetail(httpFailure(status), status)
	}
	root, err := parseNode(data)
	if err != nil {
		return failedDetail("INVALID_RESPONSE", status)
	}
	if find != "" {
		array := root
		if !array.isArray {
			array = root.at("data")
			if !array.isArray {
				array = root.at("models")
			}
		}
		if !array.isArray {
			return failedDetail("INVALID_RESPONSE", status)
		}
		for _, n := range array.array {
			if n.at("id").text() == find || n.at("name").text() == find {
				return metadata(n, status)
			}
		}
		return failedDetail("MODEL_NOT_LISTED", status)
	}
	return metadata(root, status)
}
func fallbackKind(kind string) bool {
	switch kind {
	case "NOT_FOUND", "NO_METADATA", "DERIVED_DEFAULT", "INVALID_RESPONSE", "MODEL_NOT_LISTED":
		return true
	}
	return false
}
func failureMessage(kind string, status *int) string {
	switch kind {
	case "AUTH_FAILED":
		return "认证失败（HTTP 401），请检查 API Key 与端点地址"
	case "FORBIDDEN":
		return "没有查询权限（HTTP 403），请检查密钥权限"
	case "RATE_LIMITED":
		return "端点限流（HTTP 429），请稍后重试"
	case "NOT_FOUND":
		return "端点未提供该模型的详情接口（HTTP 404/405）"
	case "MODEL_NOT_LISTED":
		return "模型列表中未找到该名称，请检查模型名；也可按供应商说明手动填写窗口"
	case "DERIVED_DEFAULT":
		return "端点只返回了默认调用参数，无法据此确认模型的上下文窗口"
	case "NO_METADATA":
		return "接口正常响应，但没有提供可识别的上下文窗口字段"
	case "TIMEOUT":
		return "查询窗口超时，请稍后重试或检查网络"
	case "NETWORK_ERROR":
		return "无法连接模型端点，请检查网络和端点地址"
	case "INVALID_RESPONSE":
		return "端点未返回有效的模型元数据 JSON，请检查端点地址"
	}
	s := "null"
	if status != nil {
		s = fmt.Sprint(*status)
	}
	return "模型端点查询失败（HTTP " + s + "），请稍后重试"
}
func windowResult(o detailOutcome, source, message *string) WindowProbeResult {
	r := WindowProbeResult{OK: o.window != nil, Window: o.window, Source: source, DetailKind: &o.kind, HTTPStatus: o.status, ReportedDefaultWindow: o.defaultWindow, Message: message}
	if !r.OK {
		r.Kind = &o.kind
		s := failureMessage(o.kind, o.status)
		r.Error = &s
	}
	if o.kind == "INPUT_LIMIT" {
		s := "input-limit"
		r.Source = &s
		m := "端点未提供总窗口，已用输入上限作保守配置"
		if o.context != nil {
			m = fmt.Sprintf("端点总窗口 %d token，已采用更小的输入上限作保守配置", *o.context)
		}
		r.Message = &m
	}
	return r
}
func (p *Probe) Window(ctx context.Context, t ProbeTarget, name string) (WindowProbeResult, error) {
	name = strip(name)
	if name == "" {
		return WindowProbeResult{}, validation("模型名不能为空")
	}
	if strip(t.BaseURL) == "" {
		return WindowProbeResult{}, validation("请先填写端点地址")
	}
	o := p.detail(ctx, t, name)
	if o.window != nil {
		s := "endpoint"
		return windowResult(o, &s, nil), nil
	}
	if fallbackKind(o.kind) {
		listed := p.outcome(ctx, t, listURL(t), "GET", nil, name)
		if listed.window != nil {
			s, m := "endpoint", "已从模型列表读取窗口"
			return windowResult(listed, &s, &m), nil
		}
		if !fallbackKind(listed.kind) || listed.kind == "MODEL_NOT_LISTED" && (o.status == nil || *o.status != 200) {
			return windowResult(listed, nil, nil), nil
		}
		ref := findReference(name, t.BaseURL)
		known := LookupKnownWindow(name)
		if ref != nil || known != nil {
			r := windowResult(o, nil, nil)
			r.OK = true
			r.Error = nil
			kind := "REFERENCE"
			r.Kind = &kind
			source := "catalog"
			m := failureMessage(o.kind, o.status) + "；提供内置参考值，不代表此端点的实测上限"
			r.Window = known
			if ref != nil {
				source = "reference"
				r.SourceURL = &ref.URL
				w := ref.Tokens
				if ref.Input != nil {
					w = min(w, *ref.Input)
				}
				r.Window = &w
				m = failureMessage(o.kind, o.status) + "；提供官方参考值，不代表此端点的实测上限"
				if ref.Input != nil {
					m += fmt.Sprintf("；官方总窗口 %d token，已采用兼容思考模式的较小输入上限作保守配置", ref.Tokens)
				}
			}
			r.Source = &source
			r.Message = &m
			return r, nil
		}
	}
	return windowResult(o, nil, nil), nil
}
func safeBody(body []byte, key string) string {
	s := string(body)
	if key != "" {
		s = strings.ReplaceAll(s, key, "[redacted]")
	}
	s = strings.Join(strings.Fields(s), " ")
	return truncateUTF16(s, 200)
}
func withBody(reason string, body []byte, key string) string {
	if s := safeBody(body, key); s != "" {
		return reason + "；响应：" + s
	}
	return reason
}
