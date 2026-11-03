package chat

import (
	"context"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func TestChatScheduleConfigurationAndCron(t *testing.T) {
	bind := func(values map[string]string) (chatSchedule, error) {
		p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
		if err != nil {
			return chatSchedule{}, err
		}
		return bindChatSchedule(p)
	}
	s, err := bind(nil)
	if err != nil || !s.janitor || s.heartbeat != 15*time.Second {
		t.Fatal(s, err)
	}
	zone := time.FixedZone("fixture", 8*60*60)
	now := time.Date(2026, 11, 4, 4, 30, 0, 0, zone)
	if !s.cron.matches(now) || s.cron.matches(now.Add(time.Second)) || s.cron.matches(now.UTC()) {
		t.Fatal("daily local 04:30 changed")
	}
	s, err = bind(map[string]string{"app.chat.heartbeat-interval": "2ms", "app.chat.attachment-janitor-enabled": "false", "app.chat.attachment-janitor-cron": "-"})
	if err != nil || s.janitor || !s.cron.disabled || s.heartbeat != 2*time.Millisecond {
		t.Fatal(s, err)
	}
	for _, values := range []map[string]string{
		{"app.chat.heartbeat-interval": "0ms"}, {"app.chat.heartbeat-interval": "-1s"}, {"app.chat.heartbeat-interval": "0.5ms"}, {"app.chat.heartbeat-interval": "bad"},
		{"app.chat.attachment-janitor-enabled": "bad"}, {"app.chat.attachment-janitor-cron": "0 0 25 * * *"}, {"app.chat.attachment-janitor-cron": "0 0 0 L * *"},
	} {
		if _, err := bind(values); err == nil {
			t.Fatal("invalid settings accepted", values)
		}
	}
	for _, expression := range []string{"0 30 4 * NOV WED", "0 0/15 4-6 ? * 1-5", "0 30 4 4,5 * ?"} {
		cron, err := parseAttachmentCron(expression)
		if err != nil || !cron.matches(now) {
			t.Fatal(expression, err)
		}
	}
	for _, expression := range []string{"", "0 0 * * *", "60 * * * * *", "0 */0 * * * *", "0 * * * * MON-", "0 * * * * 8", "0 * * ? ? *", "* * * * * * /"} {
		if _, err := parseAttachmentCron(expression); err == nil {
			t.Fatal(expression)
		}
	}
	sunday := time.Date(2026, 11, 8, 0, 0, 0, 0, zone)
	for _, expression := range []string{"@weekly", "0 0 0 * * 7", "0 0 0 * * SUN"} {
		cron, err := parseAttachmentCron(expression)
		if err != nil || !cron.matches(sunday) {
			t.Fatal(expression, err)
		}
	}
}

func TestChatJanitorSwitchCronAndCancellation(t *testing.T) {
	cron, _ := parseAttachmentCron("0 30 4 * * *")
	now := time.Date(2026, 11, 4, 4, 30, 0, 0, time.UTC)
	for _, enabled := range []bool{false, true} {
		ticks := make(chan time.Time, 2)
		ticks <- now.Add(-time.Second)
		ticks <- now
		close(ticks)
		calls := 0
		s := chatSchedule{janitor: enabled, cron: cron}
		s.runJanitor(context.Background(), time.UTC, ticks, func(context.Context, time.Time) { calls++ })
		if enabled && calls != 1 || !enabled && calls != 0 {
			t.Fatal(enabled, calls)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	chatSchedule{janitor: true, cron: cron}.runJanitor(ctx, time.UTC, make(chan time.Time), func(context.Context, time.Time) { t.Error("ran after close") })
}
