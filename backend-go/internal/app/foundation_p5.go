package app

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	web "personal-ai-os/backend-go/internal/platform/http"
)

type foundationP5 struct {
	role           config.Role
	events         *calendar.Events
	scan           config.CalendarProperties
	store          *memory.Store
	memorySettings config.MemoryProperties
	runtime        *model.Runtime
	backfill       bool

	push            *calendar.Push
	pushInterval    time.Duration
	webhook         *calendar.Webhook
	webhookInterval time.Duration
	streamInterval  time.Duration
	rabbitURI       string

	eventsHTTP, memoryHTTP, pushHTTP, streamHTTP http.Handler
	stream                                       *calendar.Stream
	broadcast                                    *calendar.Broadcast
	stop                                         context.CancelFunc
	workers                                      sync.WaitGroup
}

func readP5Properties(properties *config.Properties, keys ...string) (map[string]string, error) {
	result := make(map[string]string, len(keys))
	for _, key := range keys {
		value, found, err := properties.Get(key)
		if err != nil {
			return nil, err
		}
		if found {
			result[key] = value
		}
	}
	return result, nil
}

func newFoundationP5(properties *config.Properties, pool *database.Pool, role config.Role) (*foundationP5, error) {
	values, err := readP5Properties(properties, "app.calendar.scan-enabled", "app.calendar.scan-interval-ms",
		"app.calendar.scan-initial-delay-ms", "app.calendar.scan-lookahead", "app.calendar.scan-lookback",
		"app.calendar.default-reminder-lead", "app.calendar.max-range-days", "app.calendar.default-query-limit",
		"app.calendar.max-query-limit")
	if err != nil {
		return nil, err
	}
	settings, err := config.BindCalendarProperties(values)
	if err != nil {
		return nil, err
	}
	return &foundationP5{role: role, scan: settings, events: calendar.NewEvents(pool, settings, time.Local)}, nil
}

func (p *foundationP5) configure(ctx context.Context, f *Foundation, properties *config.Properties, logger *slog.Logger) error {
	flags, err := readP5Properties(properties, "app.web-push.enabled", "app.reminders.webhook.enabled", "app.rabbitmq.enabled")
	if err != nil {
		return err
	}
	if err := p.configureDelivery(properties, flags); err != nil {
		return err
	}
	if err := p.checkPrivileges(ctx, f.pool, flags); err != nil {
		return err
	}
	p.store, err = memory.NewStore(ctx, f.pool)
	if err != nil {
		return err
	}
	values, err := readP5Properties(properties, "app.memory.pinned-max-count", "app.memory.similar-threshold",
		"app.memory.ranking.enabled", "app.memory.ranking.timeout", "app.memory.ranking.half-life-days",
		"app.memory.backfill.enabled", "app.memory.backfill.batch-size")
	if err != nil {
		return err
	}
	p.memorySettings, err = config.BindMemoryProperties(values)
	if err != nil {
		return err
	}
	if f.models.Embedding != nil {
		tag := "unknown"
		for _, key := range []string{"spring.ai.openai.embedding.model", "spring.ai.ollama.embedding.model"} {
			value, _, err := properties.Get(key)
			if err != nil {
				return err
			}
			if value = strings.TrimSpace(value); value != "" {
				tag = value
				break
			}
		}
		p.store.UseEmbedding(f.models.Embedding, tag, p.memorySettings.SimilarThreshold)
		p.backfill = p.memorySettings.Backfill.Enabled
	}
	p.runtime = f.models.Runtime
	p.eventsHTTP = p.events.Handler(logger)
	p.memoryHTTP = memory.NewHTTP(p.store, logger)
	p.pushHTTP = p.push.Handler(logger)
	return nil
}

func (p *foundationP5) configureDelivery(properties *config.Properties, flags map[string]string) error {
	var err error
	if p.role.Allows("api") {
		p.streamInterval, err = p5Interval(properties, "app.calendar.stream-check-interval-ms", time.Second)
		if err != nil {
			return err
		}
	}
	if strings.EqualFold(flags["app.rabbitmq.enabled"], "true") && (p.role.Allows("api") || p.role.Allows("delivery")) {
		p.rabbitURI, _, err = properties.Get("app.rabbitmq.uri")
		if err != nil {
			return err
		}
		if strings.TrimSpace(p.rabbitURI) == "" {
			return errors.New("提醒广播的 RabbitMQ URI 不能为空")
		}
	}
	pushEnabled := strings.EqualFold(flags["app.web-push.enabled"], "true")
	var values map[string]string
	if pushEnabled {
		values, err = readP5Properties(properties, "app.web-push.public-key", "app.web-push.private-key", "app.web-push.subject")
		if err != nil {
			return err
		}
		p.pushInterval, err = p5Interval(properties, "app.web-push.interval-ms", 30*time.Second)
		if err != nil {
			return err
		}
	}
	p.push, err = calendar.NewPush(p.events, pushEnabled, values["app.web-push.public-key"], values["app.web-push.private-key"], values["app.web-push.subject"])
	if err != nil {
		return err
	}
	if strings.EqualFold(flags["app.reminders.webhook.enabled"], "true") {
		values, err := readP5Properties(properties, "app.reminders.webhook.url", "app.reminders.webhook.secret", "app.reminders.webhook.allow-local-http")
		if err != nil {
			return err
		}
		p.webhook, err = calendar.NewWebhook(p.events, values["app.reminders.webhook.url"], values["app.reminders.webhook.secret"],
			strings.EqualFold(values["app.reminders.webhook.allow-local-http"], "true"))
		if err != nil {
			return err
		}
		p.webhookInterval, err = p5Interval(properties, "app.reminders.webhook.poll-ms", 5*time.Second)
		if err != nil {
			return err
		}
	}
	return nil
}

func p5Interval(properties *config.Properties, key string, fallback time.Duration) (time.Duration, error) {
	value, found, err := properties.Get(key)
	if err != nil {
		return 0, err
	}
	if !found {
		return fallback, nil
	}
	interval, err := strconv.ParseInt(value, 10, 32)
	if err != nil || interval <= 0 {
		return 0, fmt.Errorf("%s 必须为正整数毫秒", key)
	}
	return time.Duration(interval) * time.Millisecond, nil
}

func (p *foundationP5) checkPrivileges(ctx context.Context, pool *database.Pool, flags map[string]string) error {
	type privilege struct{ table, rights string }
	required := []privilege{{"calendar_event", "SELECT,INSERT,UPDATE,DELETE"}}
	if p.role.Allows("api") || p.scan.ScanEnabled {
		required = append(required, privilege{"reminder", "INSERT"})
	}
	if p.role.Allows("api") {
		required = append(required, privilege{"web_push_subscription", "SELECT,DELETE"})
		if strings.EqualFold(flags["app.web-push.enabled"], "true") {
			required = append(required, privilege{"web_push_subscription", "INSERT,UPDATE"})
		}
	}
	if p.role.Allows("delivery") {
		if p.rabbitURI != "" {
			required = append(required, privilege{"reminder_broadcast_state", "SELECT,INSERT,UPDATE"})
		}
		if p.webhook != nil {
			required = append(required, privilege{"reminder_webhook_target", "SELECT,INSERT"},
				privilege{"reminder_webhook_delivery", "SELECT,INSERT,UPDATE,DELETE"})
		}
		if p.pushInterval != 0 {
			required = append(required, privilege{"web_push_subscription", "SELECT,DELETE"},
				privilege{"web_push_delivery", "SELECT,INSERT,UPDATE,DELETE"})
		}
	}
	return pool.WithConn(ctx, func(conn *sql.Conn) error {
		for _, item := range required {
			var allowed bool
			err := conn.QueryRowContext(ctx, `SELECT bool_and(has_table_privilege(current_user, $1, permission))
				FROM unnest(string_to_array($2,',')) AS permissions(permission)`, "public."+item.table, item.rights).Scan(&allowed)
			if err != nil {
				return err
			}
			if !allowed {
				return fmt.Errorf("P5运行账号缺少 %s 表权限", item.table)
			}
		}
		return nil
	})
}

func (p *foundationP5) addChatOptions(options *chat.ModuleOptions) {
	options.ExtraTools = append(agent.CalendarTools(p.events, options.Confirmations), agent.MemoryTool(p.store))
	options.MemoryPrompt = func(ctx context.Context, user, _ string) (string, error) {
		return p.store.Prompt(ctx, user, p.memorySettings)
	}
	options.MemoryCapture = func(ctx context.Context, user, reply string, limit int) error {
		return p.store.Capture(ctx, p.runtime, user, reply, limit)
	}
}

func (p *foundationP5) route(previous http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		switch {
		case calendar.OwnsStreamPath(path) && p.streamHTTP != nil:
			p.streamHTTP.ServeHTTP(w, r)
		case calendar.OwnsPushPath(path):
			p.pushHTTP.ServeHTTP(w, r)
		case calendar.OwnsEventPath(path) || calendar.OwnsReminderPath(path):
			p.eventsHTTP.ServeHTTP(w, r)
		case memory.OwnsPath(path):
			p.memoryHTTP.ServeHTTP(w, r)
		default:
			previous.ServeHTTP(w, r)
		}
	})
}

func (p *foundationP5) start(f *Foundation, logger *slog.Logger) error {
	ctx, stop := context.WithCancel(f.ctx)
	p.stop = stop
	if p.role.Allows("api") {
		p.stream = calendar.NewStream(ctx, p.events, p.streamInterval)
		p.streamHTTP = web.HandleErrors(p.stream.Serve, logger)
	}
	if p.rabbitURI != "" {
		var err error
		p.broadcast, err = calendar.StartBroadcast(ctx, p.rabbitURI, p.events, p.stream, f.coordinator, p.role.Allows("delivery"), logger)
		if err != nil {
			return err
		}
	}
	schedule := func(initial, interval time.Duration, job func(context.Context, time.Time) error, warning string) {
		p.workers.Go(func() {
			calendar.RunFixedDelay(ctx, initial, interval, func(ctx context.Context, now time.Time) {
				if err := job(ctx, now); err != nil && ctx.Err() == nil {
					logger.ErrorContext(ctx, warning)
				}
			})
		})
	}
	if p.scan.ScanEnabled {
		schedule(time.Duration(p.scan.ScanInitialDelayMS)*time.Millisecond, time.Duration(p.scan.ScanIntervalMS)*time.Millisecond,
			func(ctx context.Context, now time.Time) error { _, err := p.events.Scan(ctx, now); return err },
			"提醒扫描失败，将在下一轮重试")
	}
	if p.role.Allows("delivery") && p.pushInterval != 0 {
		schedule(15*time.Second, p.pushInterval, p.push.Dispatch, "Web Push 调度失败，将在下一轮重试")
	}
	if p.role.Allows("delivery") && p.webhook != nil {
		schedule(0, p.webhookInterval, func(ctx context.Context, _ time.Time) error { return p.webhook.Dispatch(ctx) },
			"提醒 Webhook 调度失败，将在下一轮重试")
	}
	if p.backfill {
		p.workers.Go(func() {
			if err := p.store.Backfill(ctx, p.memorySettings.Backfill.BatchSize); err != nil && ctx.Err() == nil {
				logger.WarnContext(ctx, "记忆向量回填失败")
			}
		})
	}
	return nil
}

func (p *foundationP5) close() {
	p.stop()
	if p.broadcast != nil {
		p.broadcast.Close()
	}
	if p.stream != nil {
		p.stream.Close()
	}
	p.workers.Wait()
	p.push.Close()
	if p.webhook != nil {
		p.webhook.Close()
	}
}
