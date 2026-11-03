package chat

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
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
	disabled bool
	fields   [6]map[int]bool
}

func parseAttachmentCron(expression string) (attachmentCron, error) {
	var c attachmentCron
	expression = strings.ToUpper(strings.TrimSpace(expression))
	if expression == "-" {
		c.disabled = true
		return c, nil
	}
	macros := map[string]string{"@YEARLY": "0 0 0 1 1 *", "@ANNUALLY": "0 0 0 1 1 *", "@MONTHLY": "0 0 0 1 * *", "@WEEKLY": "0 0 0 * * 0", "@DAILY": "0 0 0 * * *", "@MIDNIGHT": "0 0 0 * * *", "@HOURLY": "0 0 * * * *"}
	if v := macros[expression]; v != "" {
		expression = v
	}
	parts := strings.Fields(expression)
	bad := errors.New("附件清理cron无效：仅支持六字段数字/名称、范围、列表、步长、?及标准宏；不支持L/W/#")
	if len(parts) != 6 {
		return c, bad
	}
	months := []string{"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"}
	weekdays := []string{"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"}
	for i, name := range months {
		parts[4] = strings.ReplaceAll(parts[4], name, strconv.Itoa(i+1))
	}
	for i, name := range weekdays {
		parts[5] = strings.ReplaceAll(parts[5], name, strconv.Itoa(i))
	}
	minima, maxima := [6]int{0, 0, 0, 1, 1, 0}, [6]int{59, 59, 23, 31, 12, 7}
	for field, part := range parts {
		c.fields[field] = map[int]bool{}
		if part == "?" && (field == 3 || field == 5) {
			part = "*"
		}
		for _, entry := range strings.Split(part, ",") {
			step := 1
			bounds := strings.Split(entry, "/")
			if len(bounds) > 2 {
				return c, bad
			}
			if len(bounds) == 2 {
				var err error
				step, err = strconv.Atoi(bounds[1])
				if err != nil || step < 1 || step > maxima[field]+1 {
					return c, bad
				}
			}
			lo, hi := minima[field], maxima[field]
			if bounds[0] != "*" {
				rangeParts := strings.Split(bounds[0], "-")
				if len(rangeParts) > 2 {
					return c, bad
				}
				var err error
				lo, err = strconv.Atoi(rangeParts[0])
				if err != nil {
					return c, bad
				}
				hi = lo
				if len(rangeParts) == 2 {
					hi, err = strconv.Atoi(rangeParts[1])
				} else if len(bounds) == 2 {
					hi = maxima[field]
				}
				if err != nil {
					return c, bad
				}
			}
			if lo < minima[field] || hi > maxima[field] || lo > hi {
				return c, bad
			}
			for n := lo; n <= hi; n += step {
				v := n
				if field == 5 && v == 7 {
					v = 0
				}
				c.fields[field][v] = true
			}
		}
	}
	return c, nil
}

func (c attachmentCron) matches(now time.Time) bool {
	if c.disabled {
		return false
	}
	for i, v := range []int{now.Second(), now.Minute(), now.Hour(), now.Day(), int(now.Month()), int(now.Weekday())} {
		if !c.fields[i][v] {
			return false
		}
	}
	return true
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
