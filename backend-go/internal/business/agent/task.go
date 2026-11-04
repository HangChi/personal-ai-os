package agent

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/api"
)

func TaskTools(tasks *task.Service, confirms *task.ConfirmationRequests) []Definition {
	var out []Definition
	fields := map[string][]string{"createTask": {"title", "description", "priority", "dueAt", "tags"}, "findTasks": {"keyword", "status", "tag", "limit"}, "getTask": {"taskId"}, "updateTaskDetails": {"taskId", "title", "description", "tags"}, "setTaskCompleted": {"taskId", "completed"}, "deleteTask": {"taskIds"}, "scheduleTask": {"taskId", "dueAt"}, "setTaskPriority": {"taskId", "priority"}}
	for _, name := range []string{"createTask", "findTasks", "getTask", "updateTaskDetails", "setTaskCompleted", "deleteTask", "scheduleTask", "setTaskPriority"} {
		properties := map[string]any{}
		required := []string{}
		for _, f := range fields[name] {
			properties[f] = StringSchema()
			switch f {
			case "tags", "taskIds":
				properties[f] = map[string]any{"type": "array", "items": StringSchema()}
			case "completed":
				properties[f] = map[string]any{"type": "boolean"}
			case "limit":
				properties[f] = map[string]any{"type": "integer"}
			}
			if f == "taskId" || f == "taskIds" || f == "completed" || name == "createTask" && f == "title" || name == "setTaskPriority" && f == "priority" {
				required = append(required, f)
			}
		}
		description := map[string]string{"createTask": "创建个人任务；截止时间接受带时区的 ISO-8601 或仅日期。", "findTasks": "按关键词、状态或标签查询任务，后续操作必须使用返回的真实任务 id。", "getTask": "按真实 id 查询任务详情。", "updateTaskDetails": "修改任务标题、描述、标签；不得用此工具修改截止时间或优先级。", "setTaskCompleted": "明确标记任务完成或恢复。", "deleteTask": "申请删除任务，必须由用户在界面确认；不会立即删除。", "scheduleTask": "申请修改或清空截止时间，必须由用户在界面确认。", "setTaskPriority": "申请修改优先级，必须由用户在界面确认。"}[name]
		d := Definition{Keys: fields[name], Model: model.Tool{Name: name, Description: description, Parameters: ObjectSchema(properties, required...)}, Write: name != "getTask" && name != "findTasks", Limit: 0}
		d.Normalize = func(args map[string]any) (map[string]any, error) {
			m := map[string]any{}
			for _, f := range fields[name] {
				v := args[f]
				if v != nil {
					switch f {
					case "tags", "taskIds":
						list, ok := v.([]any)
						if !ok {
							return nil, Invalid(f + " 必须是数组")
						}
						for _, item := range list {
							if _, ok := item.(string); !ok {
								return nil, Invalid(f + " 必须是文本数组")
							}
						}
					case "completed":
						if _, ok := v.(bool); !ok {
							return nil, Invalid("completed 必须是布尔值")
						}
					case "limit":
						if _, e := Integer(args, f); e != nil {
							return nil, e
						}
					default:
						if _, ok := v.(string); !ok {
							return nil, Invalid(f + " 必须是文本")
						}
					}
				}
				m[f] = v
			}
			return m, nil
		}
		d.Action = func(ctx context.Context, t *Turn, args map[string]any) (Result, error) {
			var result Result
			var e error
			switch name {
			case "createTask":
				var priority *task.Priority
				if raw := Text(args, "priority"); Strip(raw) != "" {
					v := task.Priority(strings.ToUpper(Strip(raw)))
					if v != task.Low && v != task.Medium && v != task.High {
						return result, Invalid("优先级只能是 LOW / MEDIUM / HIGH，收到：" + Text(args, "priority"))
					}
					priority = &v
				}
				due, e := parseInstant(Text(args, "dueAt"))
				if e != nil {
					return result, e
				}
				v, e := tasks.Create(ctx, task.CreateRequest{Title: Text(args, "title"), Description: nullableText(args, "description"), Priority: priority, DueAt: due, Tags: stringArray(args["tags"])})
				return OK("已创建任务「"+v.Title+"」", v.DTO(time.Now())), taskError(e)
			case "findTasks":
				q := task.Query{Keyword: Text(args, "keyword"), Tag: Text(args, "tag"), Size: 20}
				if args["limit"] != nil {
					q.Size, _ = Integer(args, "limit")
					q.Size = min(q.Size, 100)
				}
				if raw := strings.ToUpper(Strip(Text(args, "status"))); raw != "" {
					v := task.Status(raw)
					if v != task.Todo && v != task.Doing && v != task.Done {
						return result, Invalid("状态只能是 TODO / DOING / DONE，收到：" + Text(args, "status"))
					}
					q.Status = &v
				}
				v, e := tasks.Search(ctx, q)
				if e != nil {
					return result, taskError(e)
				}
				items := make([]task.DTO, 0, len(v.Items))
				for _, t := range v.Items {
					items = append(items, t.DTO(time.Now()))
				}
				message := fmt.Sprintf("找到 %d 个任务", len(items))
				if len(items) == 0 {
					message = "没有符合条件的任务"
				}
				return OK(message, items), nil
			case "deleteTask":
				ids := stringArray(args["taskIds"])
				if len(ids) == 0 {
					return result, Invalid("至少需要一个任务标识")
				}
				if len(ids) > 50 {
					return result, Invalid("一次最多处理 50 个任务，请拆分后重试")
				}
				for i, raw := range ids {
					if ids[i], e = ParseTaskID(raw, "taskIds"); e != nil {
						return result, taskError(e)
					}
				}
				found, e := tasks.FindByIDs(ctx, ids)
				if e != nil {
					return result, taskError(e)
				}
				ids = []string{}
				for _, v := range found {
					ids = append(ids, v.ID)
				}
				if len(ids) == 0 {
					return result, Invalid("指定的任务都不存在，未生成确认事项")
				}
				v, e := confirms.Delete(ctx, ids, t.ConversationID)
				return confirmationResult(v, name), taskError(e)
			default:
				// 保持 Java 工具的校验先后，拒绝结果也参与幂等与审计。
				if name == "updateTaskDetails" && args["title"] == nil && args["description"] == nil && args["tags"] == nil {
					return result, Invalid("至少需要提供新的标题、描述或标签")
				}
				if name == "setTaskCompleted" && args["completed"] == nil {
					return result, Invalid("必须明确指示是标记完成还是恢复")
				}
				if name == "setTaskPriority" {
					raw := Text(args, "priority")
					if Strip(raw) == "" {
						return result, Invalid("优先级不能为空")
					}
					p := task.Priority(strings.ToUpper(Strip(raw)))
					if p != task.Low && p != task.Medium && p != task.High {
						return result, Invalid("优先级只能是 LOW / MEDIUM / HIGH，收到：" + raw)
					}
				}
				id, parseErr := ParseTaskID(Text(args, "taskId"), "taskId")
				if e = parseErr; e != nil {
					return result, taskError(e)
				}
				switch name {
				case "getTask":
					v, e := tasks.Get(ctx, id)
					return OK("任务「"+v.Title+"」详情", v.DTO(time.Now())), taskError(e)
				case "updateTaskDetails":
					change := task.UpdateRequest{Title: nullableText(args, "title"), Description: nullableText(args, "description")}
					if args["tags"] != nil {
						tags := stringArray(args["tags"])
						change.Tags = &tags
					}
					if change.Title == nil && change.Description == nil && change.Tags == nil {
						return result, Invalid("至少需要提供新的标题、描述或标签")
					}
					v, e := tasks.Update(ctx, id, change)
					return OK("已更新任务「"+v.Title+"」", v.DTO(time.Now())), taskError(e)
				case "setTaskCompleted":
					b, ok := args["completed"].(bool)
					if !ok {
						return result, Invalid("必须明确指示是标记完成还是恢复")
					}
					v, e := tasks.SetCompleted(ctx, id, b)
					action := "已恢复"
					if b {
						action = "已完成"
					}
					return OK("任务「"+v.Title+"」"+action, v.DTO(time.Now())), taskError(e)
				case "scheduleTask":
					if _, e := tasks.Get(ctx, id); e != nil {
						return result, taskError(e)
					}
					due, e := parseInstant(Text(args, "dueAt"))
					if e != nil {
						return result, e
					}
					v, e := confirms.Schedule(ctx, id, due, t.ConversationID)
					return confirmationResult(v, name), taskError(e)
				case "setTaskPriority":
					if Strip(Text(args, "priority")) == "" {
						return result, Invalid("优先级不能为空")
					}
					p := task.Priority(strings.ToUpper(Strip(Text(args, "priority"))))
					if p != task.Low && p != task.Medium && p != task.High {
						return result, Invalid("优先级只能是 LOW / MEDIUM / HIGH，收到：" + Text(args, "priority"))
					}
					v, e := confirms.Priority(ctx, id, p, t.ConversationID)
					return confirmationResult(v, name), taskError(e)
				}
			}
			return result, e
		}
		out = append(out, d)
	}
	return out
}
func taskError(e error) error {
	if e == nil {
		return nil
	}
	var invalid *task.ValidationError
	if errors.As(e, &invalid) {
		return Invalid(e.Error())
	}
	if errors.Is(e, task.ErrNotFound) {
		return api.NewBusinessError(api.ResourceNotFound, "任务不存在")
	}
	return e
}
func confirmationResult(v confirmation.DTO, name string) Result {
	prefix := map[string]string{"deleteTask": "删除操作", "deleteCalendarEvent": "删除日程", "scheduleTask": "修改截止时间", "setTaskPriority": "修改优先级"}[name]
	return Result{Status: "REQUIRES_CONFIRMATION", Message: prefix + "需要你确认。已生成待确认事项：" + v.Summary + "。请用户在界面上确认后再执行。", ConfirmationID: &v.ID}
}
func nullableText(m map[string]any, k string) *string {
	v, ok := m[k].(string)
	if !ok {
		return nil
	}
	return &v
}
func stringArray(v any) []string {
	if v == nil {
		return nil
	}
	raw, _ := v.([]any)
	out := []string{}
	for _, v := range raw {
		s, _ := v.(string)
		out = append(out, s)
	}
	return out
}
func ParseToolInstant(raw string) (*time.Time, error) { return parseInstant(raw) }
func parseInstant(raw string) (*time.Time, error) {
	original := raw
	raw = Strip(raw)
	if raw == "" {
		return nil, nil
	}
	raw = strings.ReplaceAll(strings.ReplaceAll(raw, "t", "T"), "z", "Z")
	nextDay := strings.Contains(raw, "T24:00:00")
	if nextDay {
		raw = strings.Replace(raw, "T24:00:00", "T00:00:00", 1)
	}
	raw = strings.Replace(raw, ":59:60", ":59:59", 1)
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02"} {
		if v, e := time.Parse(layout, raw); e == nil {
			if nextDay {
				v = v.AddDate(0, 0, 1)
			}
			v = v.UTC()
			return &v, nil
		}
	}
	return nil, Invalid("dueAt 不是合法的时间，请使用 ISO-8601（如 2026-10-17T10:00:00Z 或 2026-10-17）：" + original)
}

// ParseTaskID 保留 Java UUID.fromString 的短组补零与分组截断。
func ParseTaskID(raw, label string) (string, error) {
	text := Strip(raw)
	if text == "" {
		return "", Invalid(label + " 不能为空")
	}
	parts := strings.Split(text, "-")
	bad := func() (string, error) { return "", Invalid(label + " 不是合法的任务标识：" + raw) }
	if len(text) > 36 || len(parts) != 5 {
		return bad()
	}
	widths := []int{8, 4, 4, 4, 12}
	out := make([]string, 5)
	for i, part := range parts {
		n, e := strconv.ParseInt(part, 16, 64)
		if e != nil || n < 0 {
			return bad()
		}
		mask := uint64(1)<<(widths[i]*4) - 1
		out[i] = fmt.Sprintf("%0*x", widths[i], uint64(n)&mask)
	}
	return strings.Join(out, "-"), nil
}

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func validateUUID(id string) error {
	if !uuidPattern.MatchString(id) {
		return Invalid("不是合法的标识")
	}
	return nil
}
