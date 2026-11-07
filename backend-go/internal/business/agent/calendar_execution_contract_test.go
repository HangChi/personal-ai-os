package agent

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

func agentCalendarRow() []driver.Value {
	a := time.Date(2026, 11, 7, 10, 0, 0, 0, time.UTC)
	return sqlValues("10000000-0000-4000-8000-000000000001", "Calendar", nil, nil, false, a, a.Add(time.Hour), "NONE", nil, 15, a)
}
func agentReminderRow() []driver.Value {
	a := time.Now()
	return sqlValues("10000000-0000-4000-8000-000000000001", "TASK", "10000000-0000-4000-8000-000000000001", "Reminder", a, a, "PENDING", nil)
}
func calendarToolsFixture(t *testing.T, steps ...sqlStep) []Definition {
	e := calendar.NewEvents(contractPool(t, steps...), config.CalendarProperties{DefaultQueryLimit: 20, MaxQueryLimit: 100, MaxRangeDays: 366, DefaultReminderLead: 15 * time.Minute}, time.UTC)
	reg, err := confirmation.NewRegistryFor(e.Actions(), e)
	if err != nil {
		t.Fatal(err)
	}
	svc, err := confirmation.NewService(&toolConfirmationStore{records: map[string]confirmation.Record{}}, reg)
	if err != nil {
		t.Fatal(err)
	}
	return CalendarTools(e, svc)
}
func toolByName(t *testing.T, defs []Definition, name string) Definition {
	t.Helper()
	for _, d := range defs {
		if d.Model.Name == name {
			return d
		}
	}
	t.Fatal(name)
	return Definition{}
}
func TestCalendarAgentToolsValidationAndExecution(t *testing.T) {
	ctx := context.Background()
	turn := &Turn{ConversationID: "conversation"}
	id := "10000000-0000-4000-8000-000000000001"
	for _, tc := range []struct {
		name string
		args map[string]any
	}{
		{"createCalendarEvent", map[string]any{"startsAt": "bad"}}, {"createCalendarEvent", map[string]any{"endsAt": "bad"}}, {"createCalendarEvent", map[string]any{"recurrenceUntil": "bad"}}, {"createCalendarEvent", map[string]any{"reminderLeadMinutes": 1.5}}, {"findCalendarEvents", map[string]any{"from": "bad"}}, {"findCalendarEvents", map[string]any{"to": "bad"}}, {"findCalendarEvents", map[string]any{"limit": 1.5}}, {"findDueReminders", map[string]any{"limit": 1.5}}, {"markReminderRead", map[string]any{"reminderId": "bad"}}, {"deleteCalendarEvent", map[string]any{"eventIds": []any{"bad"}}}, {"deleteCalendarEvent", map[string]any{}},
	} {
		t.Run(tc.name+strings.Join(func() []string {
			var a []string
			for k := range tc.args {
				a = append(a, k)
			}
			return a
		}(), ""), func(t *testing.T) {
			d := toolByName(t, calendarToolsFixture(t), tc.name)
			if _, e := d.Action(ctx, turn, tc.args); e == nil {
				t.Fatal("invalid tool args", tc.args)
			}
		})
	}
	for _, failed := range []bool{false, true} {
		t.Run("create"+map[bool]string{true: "failed"}[failed], func(t *testing.T) {
			q := queryStep("INSERT INTO calendar_event", agentCalendarRow())
			if failed {
				q.err = errors.New("database")
			}
			d := toolByName(t, calendarToolsFixture(t, q), "createCalendarEvent")
			result, e := d.Action(ctx, turn, map[string]any{"title": "Calendar", "startsAt": "2026-11-07T10:00:00Z", "endsAt": "2026-11-07T11:00:00Z", "recurrenceType": "daily", "allDay": false, "reminderLeadMinutes": json.Number("15"), "recurrenceUntil": "2026-11-10T00:00:00Z"})
			if failed {
				if e == nil {
					t.Fatal("error lost")
				}
			} else if e != nil || !strings.Contains(result.Message, "Calendar") {
				t.Fatal(result, e)
			}
		})
	}
	for _, found := range []bool{false, true} {
		t.Run("search"+map[bool]string{true: "found"}[found], func(t *testing.T) {
			q := queryStep("ORDER BY starts_at")
			if found {
				q.rows = [][]driver.Value{agentCalendarRow()}
			}
			d := toolByName(t, calendarToolsFixture(t, q), "findCalendarEvents")
			r, e := d.Action(ctx, turn, map[string]any{"from": "2026-11-07T00:00:00Z", "to": "2026-11-08T00:00:00Z", "limit": json.Number("10")})
			if e != nil || strings.Contains(r.Message, "找到") != found {
				t.Fatal(r, e)
			}
		})
	}
	for _, found := range []bool{false, true} {
		q := queryStep("FROM reminder")
		if found {
			q.rows = [][]driver.Value{agentReminderRow()}
		}
		d := toolByName(t, calendarToolsFixture(t, q), "findDueReminders")
		r, e := d.Action(ctx, turn, map[string]any{"limit": json.Number("10")})
		if e != nil || strings.Contains(r.Message, "有 1 条") != found {
			t.Fatal(r, e)
		}
	}
	d := toolByName(t, calendarToolsFixture(t, queryStep("UPDATE reminder", agentReminderRow())), "markReminderRead")
	r, e := d.Action(ctx, turn, map[string]any{"reminderId": id})
	if e != nil || !strings.Contains(r.Message, "Reminder") {
		t.Fatal(r, e)
	}
	d = toolByName(t, calendarToolsFixture(t, queryStep("FROM calendar_event WHERE", agentCalendarRow())), "deleteCalendarEvent")
	r, e = d.Action(ctx, turn, map[string]any{"eventIds": []any{id}})
	if e != nil || r.ConfirmationID == nil {
		t.Fatal(r, e)
	}
	d = toolByName(t, calendarToolsFixture(t, queryStep("FROM calendar_event WHERE")), "deleteCalendarEvent")
	if _, e = d.Action(ctx, turn, map[string]any{"eventIds": []any{id}}); e == nil {
		t.Fatal("missing target")
	}
}
