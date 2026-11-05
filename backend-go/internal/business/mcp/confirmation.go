package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"maps"
	"slices"
	"strings"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/platform/api"
)

type ToolCall struct {
	Server    string         `json:"server"`
	Tool      string         `json:"tool"`
	Arguments map[string]any `json:"arguments"`
}

func Summary(p ToolCall) string {
	summary := "MCP 工具调用确认：server=" + p.Server + "，工具=" + p.Tool
	parts := []string{}
	for _, k := range slices.Sorted(maps.Keys(p.Arguments)) {
		if len(parts) == 3 {
			parts = append(parts, fmt.Sprintf("…等 %d 个参数", len(p.Arguments)))
			break
		}
		v := strings.NewReplacer("\n", " ", "\r", " ").Replace(javaValue(p.Arguments[k]))
		units := utf16.Encode([]rune(v))
		if len(units) > 80 {
			v = string(utf16.Decode(units[:80])) + "…"
		}
		parts = append(parts, k+"="+v)
	}
	if len(parts) > 0 {
		summary += "，" + strings.Join(parts, "，")
	}
	return summary
}

func javaValue(v any) string {
	switch x := v.(type) {
	case nil:
		return "null"
	case []any:
		parts := []string{}
		for _, e := range x {
			parts = append(parts, javaValue(e))
		}
		return "[" + strings.Join(parts, ", ") + "]"
	case map[string]any:
		parts := []string{}
		for _, k := range slices.Sorted(maps.Keys(x)) {
			parts = append(parts, k+"="+javaValue(x[k]))
		}
		return "{" + strings.Join(parts, ", ") + "}"
	default:
		return fmt.Sprint(v)
	}
}

// The handler is assembled even when external MCP is disabled.
type ConfirmationHandler struct{ Registry *Registry }

func (*ConfirmationHandler) Actions() []confirmation.Action {
	return []confirmation.Action{confirmation.MCPToolCall}
}
func (h *ConfirmationHandler) Execute(ctx context.Context, _ confirmation.Action, payload string) (int, error) {
	var p ToolCall
	if err := json.Unmarshal([]byte(payload), &p); err != nil {
		return 0, api.NewBusinessError(api.ValidationFailed, "MCP 确认参数无效")
	}
	if h.Registry == nil || !h.Registry.Enabled() {
		return 0, api.NewBusinessError(api.AINotConfigured, "MCP 未启用，无法执行该确认操作")
	}
	if h.Registry.session(p.Server) == nil {
		return 0, confirmation.ErrTargetMissing
	}
	result, err := h.Registry.CallWithOperationID(ctx, p.Server, p.Tool, p.Arguments, confirmation.OperationID(ctx))
	if err != nil {
		return 0, api.NewBusinessError(api.BusinessConflict, UnknownOutcome(p.Tool))
	}
	if result.IsError {
		return 0, api.NewBusinessError(api.BusinessConflict, UnknownOutcome(p.Tool))
	}
	return 1, nil
}
