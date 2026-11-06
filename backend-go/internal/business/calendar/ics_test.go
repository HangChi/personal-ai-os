package calendar

import (
	"strings"
	"testing"
	"time"
)

func icsFixture(extra string) string {
	return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:test-1\r\nSUMMARY:例会\\,复盘\r\nDTSTART;TZID=Asia/Shanghai:20261106T090000\r\nDTEND;TZID=Asia/Shanghai:20261106T100000\r\n" + extra + "END:VEVENT\r\nEND:VCALENDAR\r\n"
}
func TestICSStrictSubset(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	rows, e := parseICS(icsFixture("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\r\n"), zone)
	if e != nil || len(rows) != 1 {
		t.Fatal(e)
	}
	if *rows[0].Event.Title != "例会,复盘" || *rows[0].Event.RecurrenceType != Weekdays || rows[0].Event.StartsAt.Format(time.RFC3339) != "2026-11-06T01:00:00Z" {
		t.Fatal(rows)
	}
	for _, extra := range []string{"RRULE:FREQ=DAILY;COUNT=5\r\n", "EXDATE:20261107T090000Z\r\n", "BEGIN:VALARM\r\nEND:VALARM\r\n"} {
		if _, e := parseICS(icsFixture(extra), zone); e == nil {
			t.Fatal("不能丢弃语义", extra)
		}
	}
	if _, e := parseICS(strings.ReplaceAll(icsFixture(""), "20261106T090000", "20260230T090000"), zone); e == nil {
		t.Fatal("应拒绝非法日期")
	}
}
func TestICSRoundTrip(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	start, _ := time.Parse(time.RFC3339, "2026-11-05T16:00:00Z")
	end := start.Add(24 * time.Hour)
	description := "a\nb,;\\"
	event := Event{ID: "00000000-0000-4000-8000-000000000001", Title: strings.Repeat("资料", 50), Description: &description, AllDay: true, StartsAt: start, EndsAt: end, RecurrenceType: None, CreatedAt: start}
	text, e := writeICS([]Event{event}, zone)
	if e != nil {
		t.Fatal(e)
	}
	for _, line := range strings.Split(text, "\r\n") {
		if len(line) > 75 {
			t.Fatal("UTF8 折行错误")
		}
	}
	rows, e := parseICS(text, zone)
	if e != nil || *rows[0].Event.Title != event.Title || *rows[0].Event.Description != description || !rows[0].Event.EndsAt.Equal(end) {
		t.Fatal(rows, e)
	}
}

func TestICSRejectsLossyRecurrence(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	for _, text := range []string{strings.ReplaceAll(icsFixture(""), "SUMMARY:", "SUMMARY;VALUE=DATE:"), strings.ReplaceAll(icsFixture("RRULE:FREQ=MONTHLY\r\n"), "20261106", "20261031"), strings.ReplaceAll(icsFixture("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\r\n"), "20261106", "20261107")} {
		if _, e := parseICS(text, zone); e == nil {
			t.Fatal("lossy input accepted")
		}
	}
}
