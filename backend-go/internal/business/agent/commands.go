package agent

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"strings"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

var commandID = regexp.MustCompile(`^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$`)

func directAllowed(name string) bool {
	if strings.HasPrefix(name, "mcp_") {
		return true
	}
	switch name {
	case "createTask", "findTasks", "getTask", "updateTaskDetails", "setTaskCompleted", "deleteTask", "scheduleTask", "setTaskPriority", "createCalendarEvent", "findCalendarEvents", "findDueReminders", "markReminderRead", "deleteCalendarEvent", "searchKnowledge", "searchWeb", "saveMemory", "getPlan":
		return true
	}
	return false
}

type DirectToolDescription struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	InputSchema string `json:"inputSchema"`
}
type DirectToolRequest struct {
	Tool      string         `json:"tool"`
	Arguments map[string]any `json:"arguments"`
}
type DirectToolExecution struct {
	RequestID string `json:"requestId"`
	Tool      string `json:"tool"`
	Result    Result `json:"result"`
	Replayed  bool   `json:"replayed"`
}

func (r *Registry) DirectTools() []DirectToolDescription {
	result := []DirectToolDescription{}
	for _, tool := range r.Definitions() {
		if !directAllowed(tool.Name) {
			continue
		}
		schema, _ := idempotency.CanonicalJSON(tool.Parameters)
		result = append(result, DirectToolDescription{tool.Name, tool.Description, string(schema)})
	}
	return result
}

// ExecuteCommand makes retries safe for reads as well as writes, and binds a
// request identity to its exact conversation, tool and arguments. No model runs.
func (r *Registry) ExecuteCommand(ctx context.Context, conversation, requestID string, request DirectToolRequest, properties config.AgentExecutionProperties) (DirectToolExecution, error) {
	if !commandID.MatchString(requestID) {
		return DirectToolExecution{}, Invalid("快捷操作必须携带 UUID 格式的 Idempotency-Key")
	}
	if _, ok := r.definitions[request.Tool]; !ok || !directAllowed(request.Tool) {
		return DirectToolExecution{}, Invalid("工具不存在或不支持直接调用，请输入 /tools 查看可用工具")
	}
	if request.Arguments == nil {
		return DirectToolExecution{}, Invalid("arguments 必须是 JSON 对象")
	}
	arguments, err := idempotency.CanonicalJSON(request.Arguments)
	if err != nil || len(arguments) > 65536 {
		return DirectToolExecution{}, Invalid("工具参数不能超过 64KB")
	}
	requestID = strings.ToLower(requestID)
	turnID := "cmd:" + requestID
	digest, err := idempotency.Digest(map[string]any{"conversationId": conversation, "tool": request.Tool, "arguments": request.Arguments})
	if err != nil {
		return DirectToolExecution{}, err
	}
	saved, err := r.idem.Execute(ctx, idempotency.Request{Key: turnID, Source: idempotency.SourceTool, Operation: "directCommand", Digest: digest, ConversationID: conversation, TurnID: turnID}, func(actionCtx context.Context) (idempotency.Result, error) {
		turn := &Turn{ConversationID: conversation, ID: turnID, Budget: NewTurnBudget(properties)}
		call := model.ToolCall{}
		call.Function.Name, call.Function.Arguments = request.Tool, string(arguments)
		result, e := r.Execute(actionCtx, turn, call)
		if e != nil {
			return idempotency.Result{}, e
		}
		outcome := idempotency.Success
		switch result.Status {
		case "REQUIRES_CONFIRMATION":
			outcome = idempotency.RequiresConfirmation
		case "REJECTED":
			outcome = idempotency.Rejected
		case "ERROR":
			outcome = idempotency.Failed
		}
		payload, e := idempotency.CanonicalJSON(result)
		if e != nil {
			return idempotency.Result{}, e
		}
		text := string(payload)
		return idempotency.NewResult(&text, outcome), nil
	})
	if err != nil {
		var conflict *idempotency.ConflictError
		if errors.As(err, &conflict) {
			code := api.BusinessConflict
			if conflict.Kind == idempotency.ErrDigestConflict {
				code = api.IdempotencyKeyConflict
			}
			return DirectToolExecution{}, api.NewBusinessError(code, conflict.Error())
		}
		return DirectToolExecution{}, err
	}
	var result Result
	if saved.ResponsePayload == nil || json.Unmarshal([]byte(*saved.ResponsePayload), &result) != nil {
		return DirectToolExecution{}, Conflict("工具执行结果未确认，请核对活动记录")
	}
	return DirectToolExecution{requestID, request.Tool, result, saved.Replayed}, nil
}
