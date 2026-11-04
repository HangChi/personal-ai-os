package agent

import (
	"context"
	"fmt"
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
		Model: model.Tool{Name: "saveMemory"},
		Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
			entry, err := store.Create(ctx, Text(args, "content"))
			return OK("已记住："+entry.Content, entry), err
		},
	}
}

func CalendarTools(events *calendar.Events, confirms *confirmation.Service) []Definition {
	fields := []string{"title", "startsAt", "endsAt", "location", "description", "allDay", "recurrenceType", "recurrenceUntil", "reminderLeadMinutes"}
	return []Definition{
		{Keys: fields, Write: true, Model: model.Tool{Name: "createCalendarEvent"},
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
		{Keys: []string{"from", "to", "limit"}, Model: model.Tool{Name: "findCalendarEvents"},
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
				message := "这个时间段没有日程安排"
				if len(page.Items) != 0 {
					message = fmt.Sprintf("找到 %d 项安排", len(page.Items))
				}
				return OK(message, page.Items), err
			}},
		{Keys: []string{"limit"}, Model: model.Tool{Name: "findDueReminders"},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				var size int
				if args["limit"] != nil {
					var err error
					size, err = Integer(args, "limit")
					if err != nil {
						return Result{}, err
					}
				}
				items, err := events.Reminders(ctx, "PENDING", true, size, time.Now())
				message := "当前没有到点的提醒"
				if len(items) != 0 {
					message = fmt.Sprintf("有 %d 条已到点的提醒", len(items))
				}
				return OK(message, items), err
			}},
		{Keys: []string{"reminderId"}, Write: true, Model: model.Tool{Name: "markReminderRead"},
			Action: func(ctx context.Context, _ *Turn, args map[string]any) (Result, error) {
				id, err := ParseTaskID(Text(args, "reminderId"), "reminderId")
				if err != nil {
					return Result{}, err
				}
				item, err := events.MarkRead(ctx, id, time.Now())
				return OK("已把提醒「"+item.Title+"」标记为已处理", item), err
			}},
		{Keys: []string{"eventIds"}, Write: true, Model: model.Tool{Name: "deleteCalendarEvent"},
			Action: func(ctx context.Context, turn *Turn, args map[string]any) (Result, error) {
				ids := stringArray(args["eventIds"])
				for i, raw := range ids {
					id, err := ParseTaskID(raw, "eventIds")
					if err != nil {
						return Result{}, err
					}
					ids[i] = id
				}
				result, err := events.RequestDelete(ctx, confirms, ids, turn.ConversationID)
				if err != nil {
					return Result{}, err
				}
				return confirmationResult(result, "deleteCalendarEvent"), nil
			}},
	}
}
