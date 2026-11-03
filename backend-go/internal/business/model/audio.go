package model

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"time"
	"unicode/utf16"
)

func truncateUTF16(s string, limit int) string {
	u := utf16.Encode([]rune(s))
	if len(u) <= limit {
		return s
	}
	u = u[:limit]
	for i, v := range u {
		if 0xd800 <= v && v <= 0xdbff && (i+1 == len(u) || u[i+1] < 0xdc00 || u[i+1] > 0xdfff) {
			u[i] = '?'
		}
	}
	return string(utf16.Decode(u)) + "…"
}

type ConnectionResult struct {
	OK           bool    `json:"ok"`
	LatencyMS    int64   `json:"latencyMs"`
	Model        *string `json:"model"`
	ReplyPreview *string `json:"replyPreview"`
	Error        *string `json:"error"`
}

func (r *Runtime) Test(ctx context.Context) ConnectionResult {
	c := r.Current()
	if c == nil {
		s := "当前没有可用的聊天模型，请先保存模型配置或检查 AI_CHAT_PROVIDER"
		return ConnectionResult{Error: &s}
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	start := time.Now()
	name := c.Name()
	reply, err := c.Call(ctx, ChatRequest{Messages: []Message{{Role: "user", Content: "请只回复两个字：正常"}}})
	result := ConnectionResult{LatencyMS: time.Since(start).Milliseconds(), Model: &name}
	if err == nil {
		s := truncateUTF16(strip(reply.Content), 60)
		result.OK = true
		result.ReplyPreview = &s
		return result
	}
	reason := "模型端点网络连接失败"
	var up *UpstreamError
	if errors.As(err, &up) {
		switch up.Status {
		case 401:
			reason = "认证失败（401）：API Key 无效或已过期"
		case 403:
			reason = "无权限（403）：该密钥不能访问此模型"
		case 404:
			reason = "未找到（404）：端点地址或模型名可能写错"
		case 429:
			reason = "被限流（429）：请求过于频繁或额度已用尽"
		default:
			reason = up.Error()
		}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		reason = "请求超时（超过 20 秒），请检查端点地址与网络"
	}
	if errors.Is(err, context.Canceled) {
		reason = "测试被中断"
		result.LatencyMS = 0
	}
	result.Error = &reason
	return result
}

const MaxAudioBytes = 8 * 1024 * 1024

type AudioStatus struct {
	Available bool    `json:"available"`
	Reason    *string `json:"reason"`
}
type TranscriptionResult struct {
	OK          bool    `json:"ok"`
	Text        string  `json:"text"`
	LatencyMS   int64   `json:"latencyMs"`
	Error       *string `json:"error"`
	Unavailable bool    `json:"unavailable"`
}
type AudioService struct {
	read    *ReadService
	http    *http.Client
	timeout time.Duration
}

func NewAudioService(read *ReadService, transport http.RoundTripper) *AudioService {
	return &AudioService{read, httpClient(transport), 30 * time.Second}
}
func blockAudio(v Resolved) *string {
	var s string
	if !v.HasAudioModel() {
		s = "当前生效的模型配置没有语音模型"
	} else if !v.HasAPIKey() {
		s = "当前生效的模型配置没有 API Key"
	} else {
		return nil
	}
	return &s
}
func (a *AudioService) Status(ctx context.Context) (AudioStatus, error) {
	v, err := a.read.Current(ctx)
	if err != nil {
		return AudioStatus{}, err
	}
	reason := blockAudio(v)
	return AudioStatus{reason == nil, reason}, nil
}
func transcriptionFailed(reason string) TranscriptionResult {
	return TranscriptionResult{Error: &reason}
}
func (a *AudioService) Transcribe(ctx context.Context, audio []byte, filename, contentType string) (TranscriptionResult, error) {
	v, err := a.read.Current(ctx)
	if err != nil {
		return TranscriptionResult{}, err
	}
	if reason := blockAudio(v); reason != nil {
		return TranscriptionResult{Error: reason, Unavailable: true}, nil
	}
	if len(audio) == 0 {
		return transcriptionFailed("录音为空，没有可转写的内容"), nil
	}
	if len(audio) > MaxAudioBytes {
		return transcriptionFailed("录音超过 8MB，请缩短后重试"), nil
	}
	if strip(filename) == "" {
		filename = "audio.webm"
	}
	if strip(contentType) == "" {
		contentType = "audio/webm"
	}
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err = writer.WriteField("model", value(v.AudioModel)); err != nil {
		return TranscriptionResult{}, err
	}
	header := textproto.MIMEHeader{}
	safe := strings.NewReplacer("\\", "\\\\", "\"", "\\\"", "\r", "", "\n", "").Replace(filename)
	header.Set("Content-Disposition", `form-data; name="file"; filename="`+safe+`"`)
	header.Set("Content-Type", strings.NewReplacer("\r", "", "\n", "").Replace(contentType))
	part, err := writer.CreatePart(header)
	if err != nil {
		return TranscriptionResult{}, err
	}
	if _, err = part.Write(audio); err != nil {
		return TranscriptionResult{}, err
	}
	if err = writer.Close(); err != nil {
		return TranscriptionResult{}, err
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(ctx, a.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "POST", endpoint(v.BaseURL, "/audio/transcriptions"), &body)
	if err != nil {
		return transcriptionFailed("端点地址格式不合法"), nil
	}
	req.GetBody = nil
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+strip(v.APIKey()))
	resp, err := a.http.Do(req)
	if err != nil {
		return transcriptionNetwork(ctx), nil
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return transcriptionNetwork(ctx), nil
	}
	latency := time.Since(start).Milliseconds()
	status := resp.StatusCode
	if status < 200 || status >= 300 {
		reason := fmt.Sprintf("转写失败（HTTP %d）", status)
		switch status {
		case 400:
			reason = "请求被拒绝（400）：该模型可能不支持转写，或音频格式不被接受"
		case 401:
			reason = "认证失败（401）：API Key 无效"
		case 403:
			reason = "无权限（403）：该密钥不能使用这个语音模型"
		case 404:
			reason = "接口不存在（404）：" + req.URL.Path + " 在该端点上不存在，可能它不支持 OpenAI 兼容的转写接口"
		case 413:
			reason = "音频过大（413）：请缩短录音"
		case 429:
			reason = "被限流（429）：请求过于频繁"
		}
		return transcriptionFailed(withBody(reason, data, v.APIKey())), nil
	}
	n, err := parseNode(data)
	if err != nil {
		return transcriptionFailed("响应不是合法 JSON，无法解析转写结果"), nil
	}
	if !n.isObject && !n.isArray && n.scalar == nil {
		return transcriptionFailed("响应为空，没有拿到转写文本"), nil
	}
	text, ok := n.at("text").scalar.(string)
	if !ok {
		text, ok = n.at("data").at("text").scalar.(string)
	}
	if !ok {
		return transcriptionFailed("响应里没有 text 字段，该端点可能不兼容 OpenAI 转写格式"), nil
	}
	text = strip(text)
	if text == "" {
		return TranscriptionResult{OK: true}, nil
	}
	return TranscriptionResult{OK: true, Text: text, LatencyMS: latency}, nil
}
func transcriptionNetwork(ctx context.Context) TranscriptionResult {
	reason := "网络连接失败"
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		reason = "转写超时（超过 30 秒），请检查端点地址与网络"
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		reason = "转写被中断"
	}
	return transcriptionFailed(reason)
}

func (s *Service) ProbeTarget(ctx context.Context, protocol, base, key *string, id *string) (ProbeTarget, error) {
	if err := checkLength(base, 500, "端点地址"); err != nil {
		return ProbeTarget{}, err
	}
	t := ProbeTarget{ParseProtocol(value(protocol)), value(base), value(key)}
	if strip(t.APIKey) == "" && id != nil {
		p, err := s.store.Require(ctx, *id)
		if err != nil {
			return ProbeTarget{}, err
		}
		v := s.resolver.Resolve(p.Values)
		t.APIKey = v.APIKey()
		if strip(t.BaseURL) == "" {
			t.BaseURL = v.BaseURL
		}
	}
	return t, nil
}
