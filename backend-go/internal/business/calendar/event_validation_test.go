package calendar

import (
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func eventValue[T any](v T) *T { return &v }
func calendarEventFixture() Event {
	start := time.Date(2026, 11, 7, 10, 0, 0, 0, time.UTC)
	return Event{Title: "合成日程", StartsAt: start, EndsAt: start.Add(time.Hour), RecurrenceType: None, Lead: 10}
}

func TestEventValidationRejectsUnsafeAgentInput(t *testing.T) {
	service := NewEvents(nil, config.CalendarProperties{}, time.UTC)
	start := calendarEventFixture().StartsAt
	cases := []struct {
		name, reason string
		request      EventRequest
		creating     bool
	}{
		{"blank-title", "标题", EventRequest{Title: eventValue(" ")}, false},
		{"long-title", "标题", EventRequest{Title: eventValue(strings.Repeat("字", 201))}, false},
		{"long-description", "描述", EventRequest{Description: eventValue(strings.Repeat("😀", 1001))}, false},
		{"long-location", "地点", EventRequest{Location: eventValue(strings.Repeat("字", 201))}, false},
		{"create-no-times", "不能为空", EventRequest{}, true},
		{"create-no-end", "不能为空", EventRequest{StartsAt: &start}, true},
		{"create-no-start", "不能为空", EventRequest{EndsAt: &start}, true},
		{"update-no-end", "同时", EventRequest{StartsAt: &start}, false},
		{"update-no-start", "同时", EventRequest{EndsAt: &start}, false},
		{"reversed", "结束时间", EventRequest{StartsAt: &start, EndsAt: eventValue(start.Add(-time.Hour))}, false},
		{"unknown-rule", "重复规则", EventRequest{RecurrenceType: eventValue(Recurrence("HOURLY"))}, false},
		{"early-until", "截止", EventRequest{RecurrenceUntil: eventValue(start.Add(-time.Hour))}, false},
		{"negative-lead", "不能为负", EventRequest{Lead: eventValue(-1)}, false},
		{"oversized-lead", "10080", EventRequest{Lead: eventValue(10081)}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			event := calendarEventFixture()
			err := service.apply(&event, tc.request, tc.creating)
			if err == nil || !strings.Contains(err.Error(), tc.reason) {
				t.Fatal(err)
			}
		})
	}
	event := Event{}
	if err := service.apply(&event, EventRequest{}, false); err == nil {
		t.Fatal("missing title accepted")
	}
}

func TestEventPatchPreservesUntouchedFieldsAndClearsOptionalValues(t *testing.T) {
	service := NewEvents(nil, config.CalendarProperties{}, time.FixedZone("fixture", 8*3600))
	event := calendarEventFixture()
	original := event
	event.Description = eventValue("描述")
	event.Location = eventValue("地点")
	event.RecurrenceUntil = eventValue(event.StartsAt.Add(24 * time.Hour))
	if err := service.apply(&event, EventRequest{Title: eventValue("  新日程  "), Description: eventValue(" "), Location: eventValue(" "), ClearRecurrenceUntil: true}, false); err != nil {
		t.Fatal(err)
	}
	if event.Title != "新日程" || event.Description != nil || event.Location != nil || event.RecurrenceUntil != nil || event.StartsAt != original.StartsAt || event.EndsAt != original.EndsAt || event.Lead != original.Lead {
		t.Fatal(event)
	}
	for _, rule := range []Recurrence{None, Daily, Weekly, Weekdays, Monthly} {
		start := time.Date(2026, 11, 7, 20, 0, 0, 0, time.FixedZone("offset", 8*3600))
		end := start.Add(time.Hour)
		until := start.Add(24 * time.Hour)
		if err := service.apply(&event, EventRequest{StartsAt: &start, EndsAt: &end, Description: eventValue(" 描述 "), Location: eventValue(" 地点 "), AllDay: eventValue(false), RecurrenceType: &rule, RecurrenceUntil: &until, Lead: eventValue(10080)}, true); err != nil {
			t.Fatal(err)
		}
		if event.StartsAt.Location() != time.UTC || event.EndsAt.Location() != time.UTC || event.RecurrenceType != rule || event.Lead != 10080 || *event.Description != "描述" || *event.Location != "地点" {
			t.Fatal(event)
		}
	}
}

func TestAllDayEventUsesConfiguredZoneAndEnsuresPositiveDay(t *testing.T) {
	zone := time.FixedZone("fixture", 8*3600)
	service := NewEvents(nil, config.CalendarProperties{}, zone)
	for _, days := range []int{0, 2} {
		event := calendarEventFixture()
		start := time.Date(2026, 11, 7, 23, 0, 0, 0, zone)
		end := start.AddDate(0, 0, days)
		if err := service.apply(&event, EventRequest{StartsAt: &start, EndsAt: &end, AllDay: eventValue(true), Lead: eventValue(0)}, true); err != nil {
			t.Fatal(err)
		}
		wantDays := days
		if days == 0 {
			wantDays = 1
		}
		if event.StartsAt.In(zone).Hour() != 0 || event.EndsAt.Sub(event.StartsAt) != time.Duration(wantDays)*24*time.Hour || event.StartsAt.Location() != time.UTC {
			t.Fatal(event)
		}
	}
}
