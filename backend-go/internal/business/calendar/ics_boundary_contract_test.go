package calendar

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestICSRejectsInvalidCalendarWithoutPartialImport(t *testing.T) {
	base := icsFixture("")
	event := strings.Split(strings.Split(base, "BEGIN:VEVENT\r\n")[1], "END:VEVENT")[0]
	cases := map[string]string{
		"oversized":                     strings.Repeat("x", (1<<20)+1),
		"invalid utf8":                  string([]byte{255}),
		"bare carriage return":          strings.Replace(base, "VERSION:2.0", "VERSION:2.0\rX", 1),
		"nul":                           strings.Replace(base, "VERSION:2.0", "VERSION:2.0\x00", 1),
		"leading fold":                  " fold\n" + base,
		"nested event":                  strings.Replace(base, "SUMMARY:", "BEGIN:VEVENT\r\nSUMMARY:", 1),
		"unmatched end":                 "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VEVENT\nEND:VCALENDAR",
		"duplicate uid":                 strings.Replace(base, "END:VCALENDAR", "BEGIN:VEVENT\r\n"+event+"END:VEVENT\r\nEND:VCALENDAR", 1),
		"unsupported calendar property": strings.Replace(base, "VERSION:2.0", "METHOD:REQUEST", 1),
		"missing version":               strings.Replace(base, "VERSION:2.0\r\n", "", 1),
		"unfinished event":              strings.Replace(base, "END:VEVENT\r\n", "", 1),
		"missing uid":                   strings.Replace(base, "UID:test-1\r\n", "", 1),
		"blank uid":                     strings.Replace(base, "test-1", "  ", 1),
		"long uid":                      strings.Replace(base, "test-1", strings.Repeat("😀", 257), 1),
		"control uid":                   strings.Replace(base, "test-1", "test\t1", 1),
		"blank summary":                 strings.Replace(base, "例会\\,复盘", " ", 1),
		"bad summary escape":            strings.Replace(base, "例会\\,复盘", "bad\\q", 1),
		"bad description escape":        icsFixture("DESCRIPTION:bad\\q\r\n"),
		"bad location escape":           icsFixture("LOCATION:bad\\\r\n"),
		"long summary":                  strings.Replace(base, "例会\\,复盘", strings.Repeat("😀", 101), 1),
		"long description":              icsFixture("DESCRIPTION:" + strings.Repeat("x", 2001) + "\r\n"),
		"long location":                 icsFixture("LOCATION:" + strings.Repeat("x", 201) + "\r\n"),
		"invalid end":                   strings.Replace(base, "20261106T100000", "bad", 1),
		"end before start":              strings.Replace(base, "20261106T100000", "20261106T080000", 1),
		"too long duration":             strings.Replace(base, "20261106T100000", "20281106T100000", 1),
		"mixed date types":              strings.Replace(base, "DTEND;TZID=Asia/Shanghai:20261106T100000", "DTEND;VALUE=DATE:20261107", 1),
		"rule duplicate":                icsFixture("RRULE:FREQ=DAILY;FREQ=WEEKLY\r\n"),
		"rule interval":                 icsFixture("RRULE:FREQ=DAILY;INTERVAL=2\r\n"),
		"rule frequency":                icsFixture("RRULE:FREQ=YEARLY\r\n"),
		"rule byday":                    icsFixture("RRULE:FREQ=DAILY;BYDAY=MO\r\n"),
		"rule local until":              icsFixture("RRULE:FREQ=DAILY;UNTIL=20261107T090000\r\n"),
		"rule invalid until":            icsFixture("RRULE:FREQ=DAILY;UNTIL=badZ\r\n"),
		"rule early until":              icsFixture("RRULE:FREQ=DAILY;UNTIL=20261101T000000Z\r\n"),
		"mixed recurrence zones":        strings.Replace(icsFixture("RRULE:FREQ=DAILY\r\n"), "DTEND;TZID=Asia/Shanghai:", "DTEND;TZID=UTC:", 1),
		"cancelled":                     icsFixture("STATUS:CANCELLED\r\n"),
		"transparent":                   icsFixture("TRANSP:TRANSPARENT\r\n"),
	}
	var many strings.Builder
	many.WriteString("BEGIN:VCALENDAR\nVERSION:2.0\n")
	for i := 0; i < 201; i++ {
		many.WriteString("BEGIN:VEVENT\n" + strings.Replace(event, "test-1", fmt.Sprint(i), 1) + "END:VEVENT\n")
	}
	many.WriteString("END:VCALENDAR")
	cases["event limit"] = many.String()
	for name, input := range cases {
		t.Run(name, func(t *testing.T) {
			zone, _ := time.LoadLocation("Asia/Shanghai")
			entries, err := parseICS(input, zone)
			if err == nil || entries != nil {
				t.Fatalf("invalid calendar partially imported: %#v, %v", entries, err)
			}
		})
	}
}

func TestICSPropertyAndTimeSemantics(t *testing.T) {
	for _, line := range []string{"invalid", ":value", "DTSTART;BAD=x:value", "DTSTART;TZID:value", "DTSTART;TZID=UTC;TZID=UTC:value"} {
		if _, err := icsProp(line); err == nil {
			t.Fatalf("invalid property accepted: %q", line)
		}
	}
	for _, p := range []icsProperty{
		{"DTSTART", "20261106T090000", map[string]string{"TZID": "invalid/zone"}},
		{"DTSTART", "20261106", map[string]string{"VALUE": "DATE", "TZID": "UTC"}},
		{"DTSTART", "20260230", map[string]string{"VALUE": "DATE"}},
		{"DTSTART", "20261106", map[string]string{"VALUE": "TIME"}},
		{"DTSTART", "20261106T090000Z", map[string]string{"TZID": "UTC"}},
		{"DTSTART", "badZ", nil},
		{"DTSTART", "20261101T013000", map[string]string{"TZID": "America/New_York"}},
		{"DTSTART", "20260308T023000", map[string]string{"TZID": "America/New_York"}},
	} {
		if _, err := icsTime(p, time.UTC); err == nil {
			t.Fatalf("ambiguous/invalid time accepted: %#v", p)
		}
	}
	zone, _ := time.LoadLocation("Asia/Shanghai")
	entries, err := parseICS(icsFixture("STATUS:CONFIRMED\r\nTRANSP:OPAQUE\r\nRRULE:FREQ=DAILY;INTERVAL=1;UNTIL=20261108T090000Z\r\n"), zone)
	if err != nil || len(entries) != 1 || entries[0].Event.RecurrenceUntil == nil || entries[0].Event.RecurrenceUntil.Format(time.RFC3339) != "2026-11-08T09:00:00Z" {
		t.Fatal(entries, err)
	}
}

func TestICSRecurringExportRoundTripPreservesUIDAndUntil(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	start := time.Date(2026, 11, 6, 0, 0, 0, 0, zone)
	until := start.AddDate(0, 0, 7)
	location := "Meeting, Room; A"
	for _, allDay := range []bool{false, true} {
		for _, rule := range []Recurrence{Daily, Weekly, Weekdays, Monthly} {
			e := Event{ID: "event-1", Title: "标题", Location: &location, StartsAt: start, EndsAt: start.Add(24 * time.Hour), AllDay: allDay, RecurrenceType: rule, RecurrenceUntil: &until, CreatedAt: start}
			output, err := writeICS([]Event{e}, zone, map[string]string{"event-1": "original-uid"})
			if err != nil {
				t.Fatal(err)
			}
			entries, err := parseICS(output, zone)
			if err != nil || len(entries) != 1 {
				t.Fatal(output, entries, err)
			}
			got := entries[0]
			if got.UID != "original-uid" || *got.Event.RecurrenceType != rule || *got.Event.AllDay != allDay || *got.Event.Location != location || !got.Event.RecurrenceUntil.Equal(until) {
				t.Fatalf("lost recurrence: %#v", got)
			}
		}
	}
	if _, err := writeICS(make([]Event, 10001), zone, nil); err == nil {
		t.Fatal("export limit ignored")
	}
	ny, _ := time.LoadLocation("America/New_York")
	if _, err := writeICS([]Event{{StartsAt: start, RecurrenceType: Daily}}, ny, nil); err == nil {
		t.Fatal("DST recurrence exported lossily")
	}
}
