package calendar

import (
	"strings"
	"testing"
	"time"
)

func TestP8EventSharedJavaTextNormalization(t *testing.T) {
	service := &Events{zone: time.UTC}
	start, end := time.Now().UTC(), time.Now().UTC().Add(time.Hour)
	for _, title := range []string{"  " + strings.Repeat("a", 200) + "  ", "\u00a0kept\u00a0", "\u3000title\u3000"} {
		description, location := " \u3000 ", "\u00a0place\u00a0"
		var event Event
		err := service.apply(&event, EventRequest{Title: &title, Description: &description, Location: &location, StartsAt: &start, EndsAt: &end}, true)
		if err != nil || event.Description != nil || event.Location == nil || *event.Location != location {
			t.Fatal("shared normalization", err)
		}
		if strings.Contains(title, "title") && event.Title != "title" {
			t.Fatal("Java ideographic strip")
		}
		if strings.Contains(title, "kept") && event.Title != title {
			t.Fatal("Java nonbreaking space must remain")
		}
	}
	for _, title := range []string{"  ", strings.Repeat("a", 201)} {
		if err := service.apply(&Event{}, EventRequest{Title: &title, StartsAt: &start, EndsAt: &end}, true); err == nil {
			t.Fatal("invalid title accepted")
		}
	}
}
