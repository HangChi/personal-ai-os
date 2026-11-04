package agent

import (
	"context"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/model"
)

func MemoryTool(store *memory.Store) Definition {
	return Definition{
		Keys: []string{"content"}, Write: true,
		Model: model.Tool{Name: "saveMemory", Description: "保存用户明确要求长期记住的事实、偏好或约束。", Parameters: ObjectSchema(map[string]any{"content": StringSchema()}, "content")},
		Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
			entry, err := store.Create(ctx, Text(args, "content"))
			return OK("已保存长期记忆", entry), err
		},
	}
}

func CalendarTools(events *calendar.Events, confirms *confirmation.Service) []Definition {
	fields := []string{"title", "startsAt", "endsAt", "location", "description", "allDay", "recurrenceType", "recurrenceUntil", "reminderLeadMinutes"}
	create := map[string]any{}
	for _, field := range fields {
		create[field] = StringSchema()
	}
	create["allDay"] = map[string]any{"type": "boolean"}
	create["reminderLeadMinutes"] = map[string]any{"type": "integer"}
	limit := map[string]any{"type": "integer"}
	return []Definition{
		{Keys: fields, Write: true, Model: model.Tool{Name: "createCalendarEvent", Description: "创建日程。相对时间按系统提示解释，起止时间使用 ISO-8601。", Parameters: ObjectSchema(create, "title", "startsAt", "endsAt")},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				req := calendar.EventRequest{Title: nullableText(args, "title"), Location: nullableText(args, "location"), Description: nullableText(args, "description")}
				for _, field := range []struct {
					key string
					dst **time.Time
				}{{"startsAt", &req.StartsAt}, {"endsAt", &req.EndsAt}, {"recurrenceUntil", &req.RecurrenceUntil}} {
					value, err := ParseToolInstant(Text(args, field.key))
					if err != nil {
						return Result{}, Invalid(field.key + " 不是合法的时间，请使用 ISO-8601")
					}
					*field.dst = value
				}
				if rule := strings.ToUpper(Strip(Text(args, "recurrenceType"))); rule != "" {
					value := calendar.Recurrence(rule)
					req.RecurrenceType = &value
				}
				if allDay, ok := args["allDay"].(bool); ok {
					req.AllDay = &allDay
				}
				if args["reminderLeadMinutes"] != nil {
					value, err := Integer(args, "reminderLeadMinutes")
					if err != nil {
						return Result{}, err
					}
					req.Lead = &value
				}
				event, err := events.Create(ctx, req)
				return OK("已创建日程「"+event.Title+"」", event), err
			}},
		{Keys: []string{"from", "to", "limit"}, Model: model.Tool{Name: "findCalendarEvents", Description: "按时间区间查询日程发生实例。", Parameters: ObjectSchema(map[string]any{"from": StringSchema(), "to": StringSchema(), "limit": limit})},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				from, err := ParseToolInstant(Text(args, "from"))
				if err != nil {
					return Result{}, Invalid("from 不是合法的时间，请使用 ISO-8601")
				}
				to, err := ParseToolInstant(Text(args, "to"))
				if err != nil {
					return Result{}, Invalid("to 不是合法的时间，请使用 ISO-8601")
				}
				var size int
				if args["limit"] != nil {
					size, err = Integer(args, "limit")
					if err != nil {
						return Result{}, err
					}
				}
				page, err := events.Search(ctx, from, to, 0, size)
				return OK("日程查询完成", page.Items), err
			}},
		{Keys: []string{"limit"}, Model: model.Tool{Name: "findDueReminders", Description: "查询已到点且未读的提醒。", Parameters: ObjectSchema(map[string]any{"limit": limit})},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				size := 50
				if args["limit"] != nil {
					var err error
					size, err = Integer(args, "limit")
					if err != nil {
						return Result{}, err
					}
				}
				items, err := events.Reminders(ctx, "PENDING", true, size, time.Now())
				return OK("提醒查询完成", items), err
			}},
		{Keys: []string{"reminderId"}, Write: true, Model: model.Tool{Name: "markReminderRead", Description: "将提醒标为已读。", Parameters: ObjectSchema(map[string]any{"reminderId": StringSchema()}, "reminderId")},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				id, err := ParseTaskID(Text(args, "reminderId"), "reminderId")
				if err != nil {
					return Result{}, err
				}
				item, err := events.MarkRead(ctx, id, time.Now())
				return OK("提醒已标记为已读", item), err
			}},
		{Keys: []string{"eventIds"}, Write: true, Model: model.Tool{Name: "deleteCalendarEvent", Description: "申请删除日程，需用户确认。", Parameters: ObjectSchema(map[string]any{"eventIds": map[string]any{"type": "array", "items": StringSchema()}}, "eventIds")},
			Action: func(ctx context.Context, turn *Turn, args map[string]any) (Result, error) {
				ids := stringArray(args["eventIds"])
				result, err := events.RequestDelete(ctx, confirms, ids, turn.ConversationID)
				if err != nil {
					return Result{}, err
				}
				return confirmationResult(result, "deleteCalendarEvent"), nil
			}},
	}
}
