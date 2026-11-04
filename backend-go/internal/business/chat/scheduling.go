package chat

import (
	"context"
	"errors"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/schedule"
)

type chatSchedule struct {
	heartbeat time.Duration
	janitor   bool
	cron      attachmentCron
}

func bindChatSchedule(p *config.Properties) (chatSchedule, error) {
	get := func(key, fallback string) (string, error) {
		v, found, err := p.Get(key)
		if !found {
			v = fallback
		}
		return v, err
	}
	h, err := get("app.chat.heartbeat-interval", "15s")
	if err != nil {
		return chatSchedule{}, err
	}
	d, err := config.ParseDuration(h)
	// Spring schedules in milliseconds and rejects non-positive periods.
	if err != nil || d == nil || *d < time.Millisecond {
		return chatSchedule{}, errors.New("对话心跳间隔必须至少为1毫秒")
	}
	enabled, err := get("app.chat.attachment-janitor-enabled", "true")
	if err != nil {
		return chatSchedule{}, err
	}
	var on bool
	switch strings.ToLower(strings.TrimSpace(enabled)) {
	case "true", "on", "yes", "1":
		on = true
	case "false", "off", "no", "0":
	default:
		return chatSchedule{}, errors.New("附件清理开关配置无效")
	}
	expression, err := get("app.chat.attachment-janitor-cron", "0 30 4 * * *")
	if err != nil {
		return chatSchedule{}, err
	}
	cron, err := parseAttachmentCron(expression)
	return chatSchedule{heartbeat: time.Duration(d.Milliseconds()) * time.Millisecond, janitor: on, cron: cron}, err
}

// Six-field Spring cron with names, lists, ranges, steps, ?, and macros.
// Extended L/W/# expressions are rejected explicitly before any worker starts.
type attachmentCron struct {
	disabled   bool
	expression schedule.Cron
}

func parseAttachmentCron(expression string) (attachmentCron, error) {
	c, err := schedule.Parse(expression)
	return attachmentCron{disabled: c.Disabled(), expression: c}, err
}

func (c attachmentCron) matches(now time.Time) bool {
	return c.expression.Matches(now)
}

func (s chatSchedule) runJanitor(ctx context.Context, zone *time.Location, ticks <-chan time.Time, prune func(context.Context, time.Time)) {
	if !s.janitor || s.cron.disabled {
		return
	}
	for {
		select {
		case <-ctx.Done():
			return
		case now, ok := <-ticks:
			if !ok {
				return
			}
			if s.cron.matches(now.In(zone)) {
				prune(ctx, now)
			}
		}
	}
}
