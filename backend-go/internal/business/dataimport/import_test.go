package dataimport

import (
	"strings"
	"testing"
)

func TestP7ImportFormatAndSizeValidation(t *testing.T) {
	for _, raw := range []string{"", `null`, `{}`, `{broken`, `{"formatVersion":"2"}`, `{"formatVersion":"1"} {}`, `{"formatVersion":"1","tasks":[{"id":"not-uuid"}]}`, `{"FormatVersion":"1"}`, `{"formatVersion":"1","tasks":[{"title":{}}]}`, `{"formatVersion":"1","tasks":[{"tags":{}}]}`, `{"formatVersion":"1","memories":[{"hitCount":2147483648}]}`, `{"formatVersion":"1","knowledgeDocuments":[{"document":{"id":"invalid"}}]}`, `{"formatVersion":"1","knowledgeDocuments":[{"source":{"spaceId":"invalid"}}]}`} {
		if _, e := Parse([]byte(raw)); e == nil {
			t.Fatal("accepted", raw)
		}
	}
	if _, e := Parse([]byte(`{"formatVersion":" 1 ","future":true,"tasks":[]}`)); e != nil {
		t.Fatal(e)
	}
	if _, e := Parse([]byte(strings.Repeat("x", MaxBytes+1))); e == nil {
		t.Fatal("20MB bound lost")
	}
}

func TestP7ImportExactFieldsAndJacksonScalarCoercion(t *testing.T) {
	p, e := Parse([]byte(`{"formatVersion":1,"FormatVersion":"2","tasks":[{"ID":"invalid","title":42,"description":true,"priority":"HIGH","tags":["a",3,null]}],"memories":[{"pinned":"TRUE","hitCount":2.9}],"calendarEvents":[{"allDay":1,"reminderLeadMinutes":"20"}],"knowledgeDocuments":[{"document":{"sizeBytes":"4294967296"}}]}`))
	if e != nil {
		t.Fatal(e)
	}
	if p.Tasks[0].ID != nil || p.Tasks[0].Title != "42" || *p.Tasks[0].Description != "true" || p.Tasks[0].Tags[1] != "3" || !p.Memories[0].Pinned || p.Memories[0].HitCount != 2 || !p.CalendarEvents[0].AllDay || *p.CalendarEvents[0].Lead != 20 || p.Documents[0].Document.SizeBytes != 4294967296 {
		t.Fatal(p)
	}
}
