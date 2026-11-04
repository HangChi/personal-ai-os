package mcp

import (
	"context"
	"encoding/json"
	"errors"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"net/http"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/api"
	"strings"
	"time"
)

type PersonalServices struct {
	Tasks     *task.Service
	Knowledge *knowledge.Retriever
	Calendar  *calendar.Events
	Memories  *memory.Store
}
type PersonalTool struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	ReadOnly    bool   `json:"readOnly"`
}
type PersonalStatus struct {
	Enabled  bool           `json:"enabled"`
	Name     string         `json:"name"`
	Version  string         `json:"version"`
	Endpoint string         `json:"endpoint"`
	Tools    []PersonalTool `json:"tools"`
}

var personalTools = []PersonalTool{
	{"personal_list_tasks", "查询个人任务，可按状态和关键词筛选，并返回分页结果。", true},
	{"personal_search_knowledge", "在个人知识库中进行混合检索，返回相关文档片段及来源。", true},
	{"personal_list_calendar", "查询个人日程发生实例，时间使用 ISO-8601 格式。", true},
	{"personal_list_memories", "读取个人长期记忆，包括内容、来源、置顶状态和更新时间。", true},
}

func (o PersonalOptions) Status() PersonalStatus {
	return PersonalStatus{o.Enabled, o.Name, o.Version, o.Endpoint, append([]PersonalTool{}, personalTools...)}
}

func NewPersonalServer(o PersonalOptions, s PersonalServices) http.Handler {
	server := sdk.NewServer(&sdk.Implementation{Name: o.Name, Version: o.Version}, nil)
	props := []map[string]any{
		{"status": map[string]any{"type": "string"}, "keyword": map[string]any{"type": "string"}, "page": map[string]any{"type": "integer"}, "size": map[string]any{"type": "integer"}},
		{"query": map[string]any{"type": "string"}, "topK": map[string]any{"type": "integer"}},
		{"from": map[string]any{"type": "string"}, "to": map[string]any{"type": "string"}, "page": map[string]any{"type": "integer"}, "size": map[string]any{"type": "integer"}}, {},
	}
	for i, t := range personalTools {
		schema := map[string]any{"type": "object", "properties": props[i]}
		if i == 1 {
			schema["required"] = []string{"query"}
		}
		no := false
		server.AddTool(&sdk.Tool{Name: t.Name, Description: t.Description, InputSchema: schema, Annotations: &sdk.ToolAnnotations{ReadOnlyHint: true, IdempotentHint: true, DestructiveHint: &no, OpenWorldHint: &no}}, func(ctx context.Context, r *sdk.CallToolRequest) (*sdk.CallToolResult, error) {
			v, e := s.call(ctx, t.Name, r.Params.Arguments)
			if e != nil {
				message := "工具执行失败"
				var business *api.BusinessError
				if errors.As(e, &business) {
					message = business.Error()
				}
				return &sdk.CallToolResult{IsError: true, Content: []sdk.Content{&sdk.TextContent{Text: message}}}, nil
			}
			raw, e := json.Marshal(v)
			if e != nil {
				return nil, e
			}
			return &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: string(raw)}}}, nil
		})
	}
	handler := sdk.NewStreamableHTTPHandler(func(*http.Request) *sdk.Server { return server }, &sdk.StreamableHTTPOptions{Stateless: true, JSONResponse: true})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !o.Enabled || r.URL.Path != o.Endpoint {
			http.NotFound(w, r)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		handler.ServeHTTP(w, r)
	})
}
func (s PersonalServices) call(ctx context.Context, name string, raw json.RawMessage) (any, error) {
	var args struct {
		Status  *string `json:"status"`
		Keyword string  `json:"keyword"`
		Page    *int    `json:"page"`
		Size    *int    `json:"size"`
		Query   *string `json:"query"`
		TopK    *int    `json:"topK"`
		From    *string `json:"from"`
		To      *string `json:"to"`
	}
	if len(raw) > 0 && json.Unmarshal(raw, &args) != nil {
		return nil, api.NewBusinessError(api.ValidationFailed, "工具参数无效")
	}
	page, size := 0, 0
	if args.Page != nil {
		page = *args.Page
	}
	if args.Size != nil {
		size = *args.Size
	}
	switch name {
	case "personal_list_tasks":
		var status *task.Status
		if args.Status != nil && strings.TrimSpace(*args.Status) != "" {
			v := task.Status(strings.ToUpper(strings.TrimFunc(*args.Status, func(r rune) bool { return r <= 0x20 })))
			if v != task.Todo && v != task.Doing && v != task.Done {
				return nil, api.NewBusinessError(api.ValidationFailed, "status 必须是 TODO、DOING 或 DONE")
			}
			status = &v
		}
		p, e := s.Tasks.Search(ctx, task.Query{Status: status, Keyword: args.Keyword, Page: page, Size: size})
		if e != nil {
			return nil, e
		}
		items := []task.DTO{}
		for _, t := range p.Items {
			items = append(items, t.DTO(time.Now()))
		}
		return api.Page[task.DTO]{Items: items, Page: p.Page, Size: p.Size, Total: p.Total}, nil
	case "personal_search_knowledge":
		if args.Query == nil {
			return nil, api.NewBusinessError(api.ValidationFailed, "检索词不能为空")
		}
		return s.Knowledge.Search(ctx, *args.Query, args.TopK)
	case "personal_list_calendar":
		from, e := parseInstant(args.From)
		if e != nil {
			return nil, e
		}
		to, e := parseInstant(args.To)
		if e != nil {
			return nil, e
		}
		return s.Calendar.Search(ctx, from, to, page, size)
	case "personal_list_memories":
		return s.Memories.List(ctx)
	}
	return nil, errors.New("工具未注册")
}
func parseInstant(s *string) (*time.Time, error) {
	if s == nil || strings.TrimSpace(*s) == "" {
		return nil, nil
	}
	v, e := time.Parse(time.RFC3339Nano, strings.TrimFunc(*s, func(r rune) bool { return r <= 0x20 }))
	if e != nil {
		return nil, api.NewBusinessError(api.ValidationFailed, "时间必须是 ISO-8601 格式，例如 2026-11-01T00:00:00Z")
	}
	return &v, nil
}
