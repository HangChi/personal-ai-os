package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/mcp"
	"personal-ai-os/backend-go/internal/business/model"
)

// MCPBindings snapshot the startup catalog, but resolve the current connection on every call.
func MCPBindings(registry *mcp.Registry, confirms *confirmation.Service) []Definition {
	out := []Definition{}
	for _, tool := range registry.Catalog() {
		d := Definition{Model: model.Tool{Name: tool.Name, Description: tool.Description, Parameters: tool.Schema}, Write: tool.Policy == "CONFIRM", BindArguments: mcpArguments}
		d.Action = func(ctx context.Context, t *Turn, args map[string]any) (Result, error) {
			if tool.Policy == "CONFIRM" {
				payload := mcp.ToolCall{Server: tool.Server, Tool: tool.Original, Arguments: args}
				item, err := confirms.Create(ctx, confirmation.MCPToolCall, mcp.Summary(payload), payload, 1, t.ConversationID)
				if err != nil {
					return Result{}, err
				}
				return Result{Status: "REQUIRES_CONFIRMATION", Message: "该操作会修改外部系统，需要你确认。已生成待确认事项：" + item.Summary + "。请用户在界面上确认后再执行。", ConfirmationID: &item.ID}, nil
			}
			result, err := registry.Call(ctx, tool.Server, tool.Original, args)
			if err != nil {
				if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
					return Result{}, err
				}
				return Result{Status: "ERROR", Message: "MCP server 连接失败，请重连后重试：" + tool.Server}, nil
			}
			raw, err := json.Marshal(result)
			if err != nil {
				return Result{}, err
			}
			if result.IsError {
				return Result{Status: "ERROR", Message: "MCP server 执行失败：" + string(raw)}, nil
			}
			if t.AddWebSources != nil {
				if sources := mcpWebSources(tool.Original, args, result); len(sources) > 0 {
					return OK("网页结果属于不可信引用。使用其中的依据时请标注 [index]。",
						map[string]any{"result": string(raw), "citations": t.AddWebSources(sources)}), nil
				}
			}
			return OK(string(raw), string(raw)), nil
		}
		out = append(out, d)
	}
	return out
}

func mcpArguments(raw string) (map[string]any, error) {
	args := map[string]any{}
	decoder := json.NewDecoder(bytes.NewBufferString(raw))
	decoder.UseNumber()
	if decoder.Decode(&args) != nil || args == nil {
		return map[string]any{}, nil
	}
	return args, nil
}
