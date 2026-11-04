package app

import (
	"context"
	"database/sql/driver"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/health"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/migration"
	"personal-ai-os/backend-go/internal/platform/modelgate"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

// FoundationOptions中的所有外部资源都必须显式提供，不读取.env或自动发现服务。
// 专用Redis客户端是借用资源，URI/TLS由创建者决定；Foundation从不关闭借用客户端。
type FoundationOptions struct {
	Connector               driver.Connector
	Migrations              fs.FS
	Schema                  string
	AllowBaselineV1         bool
	Pool                    *database.PoolOptions
	SessionRedis, RateRedis *redisclient.Client
	Observer                ratelimit.Observer
	Business                http.Handler
	EnableP2                bool
	EnableP3                bool
	EnableP4                bool
	EnableP5                bool
	EmbeddingRedis          *redisclient.Client
	ModelTransport          http.RoundTripper
}

func (FoundationOptions) String() string               { return "FoundationOptions{redacted}" }
func (o FoundationOptions) GoString() string           { return o.String() }
func (FoundationOptions) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

// Foundation是尚未发布的P1组合入口，不启动业务Worker，也不宣告迁移就绪。
type Foundation struct {
	pool              *database.Pool
	coordinator       *coordination.Coordinator
	gate              *modelgate.Gate
	executor          *taskexec.Executor
	health            *health.Service
	history           migration.HistoryStatus
	handler           http.Handler
	management        http.Handler
	ctx               context.Context
	cancel            context.CancelFunc
	mu                sync.Mutex
	closing           bool
	served            bool
	active            sync.WaitGroup
	closeOnce         sync.Once
	done              chan struct{}
	closeErr          error
	models            *model.Module
	chat              *chat.Module
	modelTransport    *http.Transport
	stopReminders     func()
	reminderStream    *calendar.Stream
	reminderBroadcast *calendar.Broadcast
}

func (*Foundation) String() string     { return "Foundation{redacted}" }
func (f *Foundation) GoString() string { return f.String() }

// OpenFoundation先解析/校验配置与依赖，再打开自有池和只读校验Flyway历史。
// 不migrate/repair；失败时关闭已创建的池/协调器，不释放借用的Redis。
func OpenFoundation(ctx context.Context, properties *config.Properties, options FoundationOptions, logger *slog.Logger) (_ *Foundation, err error) {
	if ctx == nil || logger == nil || options.Connector == nil || options.Migrations == nil {
		return nil, errors.New("基础装配必须显式提供context、日志、连接器与迁移目录")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if options.EnableP4 && (!options.EnableP2 || !options.EnableP3) {
		return nil, errors.New("P4装配必须显式启用P2与P3依赖")
	}
	if err := properties.ValidateFoundationExtensions(); err != nil {
		return nil, err
	}
	if _, err := properties.Logging(); err != nil {
		return nil, err
	}
	settings, err := properties.Foundation()
	if err != nil {
		return nil, err
	}
	actuatorProperties, err := properties.Actuator()
	if err != nil {
		return nil, err
	}
	multipart, err := properties.Multipart()
	if err != nil {
		return nil, err
	}
	sessionProperties, err := properties.Session(settings.SharedSession)
	if err != nil {
		return nil, err
	}
	// 协调器使用基线public表；不允许仅修改历史schema却把实际协调写入另一schema。
	if options.Schema != "public" {
		return nil, errors.New("基础装配仅验收显式public schema")
	}
	if settings.ModelConcurrency > 100 {
		return nil, errors.New("基础装配共享协调容量尚不支持超过100")
	}
	if settings.SharedSession != (options.SessionRedis != nil) || settings.RedisRateLimit != (options.RateRedis != nil) {
		return nil, errors.New("专用Redis开关必须与显式依赖一致，禁止隐式回退")
	}
	if options.SessionRedis != nil && options.SessionRedis == options.RateRedis {
		return nil, errors.New("会话和限流必须显式提供各自专用Redis客户端")
	}
	scripts, err := migration.LoadVersioned(options.Migrations)
	if err != nil {
		return nil, errors.New("基础装配的显式迁移目录无效")
	}
	credentials := auth.Options{Enabled: settings.Auth.Enabled, Username: settings.Auth.Username, MaxFailures: settings.Auth.MaxFailures, Lockout: settings.Auth.Lockout}
	if password := settings.Auth.Password(); password != nil {
		credentials.Password = *password
	}
	service, err := auth.New(credentials)
	credentials.Password = ""
	if err != nil {
		return nil, err
	}
	var sessions auth.SessionStore
	if settings.SharedSession {
		sessions, err = auth.NewRedisStore(options.SessionRedis, settings.SessionNamespace, sessionProperties.Timeout)
	} else {
		sessions, err = auth.NewMemoryStore(sessionProperties.Timeout)
	}
	if err != nil {
		return nil, err
	}
	var redisBucket ratelimit.Bucket
	if settings.RedisRateLimit {
		redisBucket, err = ratelimit.NewRedisBucket(options.RateRedis, settings.RateNamespace)
		if err != nil {
			return nil, err
		}
	}
	security, err := auth.NewHTTP(service, sessions, auth.HTTPOptions{CSRFEnabled: settings.Security.CSRFEnabled, CookieSecure: settings.Security.CookieSecure, Cookie: &sessionProperties.Cookie, Multipart: httpmiddleware.MultipartLimits{MaxFileBytes: multipart.MaxFileBytes, MaxRequestBytes: multipart.MaxRequestBytes, TempDirectory: multipart.Location}}, logger)
	if err != nil {
		return nil, err
	}
	for _, client := range []*redisclient.Client{options.SessionRedis, options.RateRedis} {
		if client != nil {
			if err := client.Ping(ctx); err != nil {
				return nil, err
			}
		}
	}
	poolOptions := database.DefaultPoolOptions(settings.Role.Worker())
	if options.Pool != nil {
		poolOptions = *options.Pool
	}
	pool, err := database.Open(ctx, options.Connector, poolOptions)
	if err != nil {
		return nil, err
	}
	f := &Foundation{pool: pool, done: make(chan struct{})}
	f.ctx, f.cancel = context.WithCancel(context.Background())
	defer func() {
		if err != nil {
			f.cancel()
			if f.chat != nil {
				_ = f.chat.Close(context.Background())
			}
			if f.modelTransport != nil {
				f.modelTransport.CloseIdleConnections()
			}
			if f.coordinator != nil {
				err = errors.Join(err, f.coordinator.Close(context.Background()))
			}
			if f.executor != nil {
				f.executor.Stop()
				_ = f.executor.Wait(context.Background())
			}
			err = errors.Join(err, pool.Close())
		}
	}()
	var validator *migration.HistoryValidator
	if options.AllowBaselineV1 {
		validator, err = migration.NewHistoryValidatorWithBaselineV1(pool, scripts, options.Schema)
	} else {
		validator, err = migration.NewHistoryValidator(pool, scripts, options.Schema)
	}
	if err != nil {
		return nil, err
	}
	if f.history, err = validator.Validate(ctx); err != nil {
		return nil, err
	}
	if f.coordinator, err = coordination.New(pool); err != nil {
		return nil, err
	}
	if f.gate, err = modelgate.NewShared(settings.ModelConcurrency, settings.Role, f.coordinator); err != nil {
		return nil, err
	}
	postgresBucket, err := ratelimit.NewPostgresBucket(pool)
	if err != nil {
		return nil, err
	}
	metrics := &foundationMetrics{extra: options.Observer, started: time.Now(), httpEnabled: actuatorProperties.HTTPRequests, httpHistogram: actuatorProperties.HTTPHistogram, embeddingHistogram: actuatorProperties.EmbeddingHistogram}
	limiter, err := ratelimit.NewShared(ratelimit.Options{Enabled: settings.RateLimit.Enabled, Capacity: settings.RateLimit.Capacity, Period: settings.RateLimit.Period}, postgresBucket, redisBucket, metrics)
	if err != nil {
		return nil, err
	}
	probe, err := health.NewPostgresProbe(pool)
	if err != nil {
		return nil, err
	}
	name, version := "", ""
	if v := settings.App.Name(); v != nil {
		name = *v
	}
	if v := settings.App.Version(); v != nil {
		version = *v
	}
	if f.health, err = health.New(health.Options{Application: name, Version: version, ProbeTimeout: 5 * time.Second}, probe, func() bool { return f.models != nil && f.models.Runtime.Configured() }, logger); err != nil {
		return nil, err
	}
	if f.executor, err = taskexec.New(taskexec.DefaultOptions(), logger); err != nil {
		return nil, err
	}
	clients := []*redisclient.Client{options.SessionRedis, options.RateRedis}
	snapshot := f.actuatorSnapshot(actuatorProperties, clients)
	var calendarEvents *calendar.Events
	var scanSettings config.CalendarProperties
	if options.EnableP5 {
		if !options.EnableP4 {
			return nil, errors.New("P5需要启用P4")
		}
		calendarValues := map[string]string{}
		for _, key := range []string{"app.calendar.scan-enabled", "app.calendar.scan-interval-ms",
			"app.calendar.scan-initial-delay-ms", "app.calendar.scan-lookahead", "app.calendar.scan-lookback",
			"app.calendar.default-reminder-lead", "app.calendar.max-range-days", "app.calendar.default-query-limit",
			"app.calendar.max-query-limit"} {
			value, found, err := properties.Get(key)
			if err != nil {
				return nil, err
			}
			if found {
				calendarValues[key] = value
			}
		}
		calendarSettings, err := config.BindCalendarProperties(calendarValues)
		if err != nil {
			return nil, err
		}
		scanSettings = calendarSettings
		calendarEvents = calendar.NewEvents(pool, calendarSettings, time.Local)
	}
	var tasks *task.Service
	var idem *idempotency.Service
	var confirms *confirmation.Service
	if options.EnableP2 {
		if err := task.CheckPrivileges(ctx, pool); err != nil {
			return nil, err
		}
		if options.Business != nil {
			return nil, errors.New("P2业务路由与自定义业务处理器不能同时装配")
		}
		taskStore, taskErr := task.NewPostgresStore(pool)
		if taskErr != nil {
			return nil, taskErr
		}
		tasks, taskErr = task.NewService(taskStore)
		if taskErr != nil {
			return nil, taskErr
		}
		idemStore, taskErr := idempotency.NewPostgresStore(pool)
		if taskErr != nil {
			return nil, taskErr
		}
		idem, taskErr = idempotency.NewService(idemStore)
		if taskErr != nil {
			return nil, taskErr
		}
		confirmStore, taskErr := confirmation.NewPostgresStore(pool)
		if taskErr != nil {
			return nil, taskErr
		}
		handler, taskErr := task.NewConfirmationHandler(tasks)
		if taskErr != nil {
			return nil, taskErr
		}
		actions := handler.Actions()
		handlers := []confirmation.Handler{handler}
		if calendarEvents != nil {
			actions = append(actions, calendarEvents.Actions()...)
			handlers = append(handlers, calendarEvents)
		}
		registry, taskErr := confirmation.NewRegistryFor(actions, handlers...)
		if taskErr != nil {
			return nil, taskErr
		}
		confirms, taskErr = confirmation.NewService(confirmStore, registry)
		if taskErr != nil {
			return nil, taskErr
		}
		options.Business, taskErr = task.NewHTTP(tasks, idem, confirms, logger)
		if taskErr != nil {
			return nil, taskErr
		}
	}
	if options.EnableP3 {
		if options.EmbeddingRedis != nil && (options.EmbeddingRedis == options.SessionRedis || options.EmbeddingRedis == options.RateRedis) {
			return nil, errors.New("嵌入缓存必须使用专用Redis客户端")
		}
		transport := options.ModelTransport
		if transport == nil {
			f.modelTransport = model.NewTransport()
			transport = f.modelTransport
		}
		f.models, err = model.OpenModule(ctx, pool, properties, transport, options.EmbeddingRedis, logger)
		if err != nil {
			return nil, err
		}
		previous := options.Business
		metrics.models = f.models
		options.Business = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.HasPrefix(r.URL.Path, "/api/v1/settings/model") || r.URL.Path == "/api/v1/chat/transcribe" || r.URL.Path == "/api/v1/chat/transcribe/status" {
				f.models.Handler.ServeHTTP(w, r)
				return
			}
			if previous != nil {
				previous.ServeHTTP(w, r)
				return
			}
			httpmiddleware.HandleErrors(func(http.ResponseWriter, *http.Request) error { return httpmiddleware.NotFound() }, logger).ServeHTTP(w, r)
		})
	}
	var push *calendar.Push
	var pushInterval = 30 * time.Second
	var webhook *calendar.Webhook
	var webhookInterval = 5 * time.Second
	var rabbitURI string
	var streamInterval time.Duration
	var streamHandler http.Handler

	var extraTools []agent.Definition
	var memoryCapture func(context.Context, string, string, int) error
	var backfillMemory func(context.Context) error
	var memoryPrompt func(context.Context, string, string) (string, error)
	if options.EnableP5 {
		events := calendarEvents.Handler(logger)
		value, _, err := properties.Get("app.calendar.stream-check-interval-ms")
		if err != nil {
			return nil, err
		}
		interval, err := strconv.ParseInt(value, 10, 32)
		if err != nil || interval <= 0 {
			return nil, errors.New("提醒流轮询间隔必须为正整数")
		}
		streamInterval = time.Duration(interval) * time.Millisecond
		rabbitEnabled, _, err := properties.Get("app.rabbitmq.enabled")
		if err != nil {
			return nil, err
		}
		if strings.EqualFold(rabbitEnabled, "true") && (settings.Role.Allows("api") || settings.Role.Allows("delivery")) {
			rabbitURI, _, err = properties.Get("app.rabbitmq.uri")
			if err != nil {
				return nil, err
			}
		}
		webhookEnabled, _, err := properties.Get("app.reminders.webhook.enabled")
		if err != nil {
			return nil, err
		}
		if strings.EqualFold(webhookEnabled, "true") {
			address, _, err := properties.Get("app.reminders.webhook.url")
			if err != nil {
				return nil, err
			}
			secret, _, err := properties.Get("app.reminders.webhook.secret")
			if err != nil {
				return nil, err
			}
			local, _, err := properties.Get("app.reminders.webhook.allow-local-http")
			if err != nil {
				return nil, err
			}
			webhook, err = calendar.NewWebhook(calendarEvents, address, secret, strings.EqualFold(local, "true"))
			if err != nil {
				return nil, err
			}
			if value, found, err := properties.Get("app.reminders.webhook.poll-ms"); err != nil {
				return nil, err
			} else if found {
				interval, err := strconv.ParseInt(value, 10, 32)
				if err != nil || interval <= 0 {
					return nil, errors.New("提醒 Webhook 轮询间隔必须为正整数")
				}
				webhookInterval = time.Duration(interval) * time.Millisecond
			}
		}
		pushEnabled, _, err := properties.Get("app.web-push.enabled")
		if err != nil {
			return nil, err
		}
		pushConfig := make(map[string]string)
		if strings.EqualFold(pushEnabled, "true") {
			for _, key := range []string{"public-key", "private-key", "subject", "interval-ms"} {
				value, _, err := properties.Get("app.web-push." + key)
				if err != nil {
					return nil, err
				}
				pushConfig[key] = value
			}
			if value := pushConfig["interval-ms"]; value != "" {
				interval, err := strconv.ParseInt(value, 10, 32)
				if err != nil || interval <= 0 {
					return nil, errors.New("Web Push 调度间隔必须为正整数")
				}
				pushInterval = time.Duration(interval) * time.Millisecond
			}
		}
		push, err = calendar.NewPush(calendarEvents, strings.EqualFold(pushEnabled, "true"), pushConfig["public-key"], pushConfig["private-key"], pushConfig["subject"])
		if err != nil {
			return nil, err
		}
		pushHandler := push.Handler(logger)
		store, err := memory.NewStore(ctx, pool)
		if err != nil {
			return nil, err
		}
		values := map[string]string{}
		for _, key := range []string{"app.memory.pinned-max-count", "app.memory.similar-threshold",
			"app.memory.ranking.enabled", "app.memory.ranking.timeout", "app.memory.ranking.half-life-days",
			"app.memory.backfill.enabled", "app.memory.backfill.batch-size"} {
			value, found, err := properties.Get(key)
			if err != nil {
				return nil, err
			}
			if found {
				values[key] = value
			}
		}
		settings, err := config.BindMemoryProperties(values)
		if err != nil {
			return nil, err
		}
		if f.models.Embedding != nil {
			tag := "unknown"
			for _, key := range []string{"spring.ai.openai.embedding.model", "spring.ai.ollama.embedding.model"} {
				value, _, err := properties.Get(key)
				if err != nil {
					return nil, err
				}
				if strings.TrimSpace(value) != "" {
					tag = strings.TrimSpace(value)
					break
				}
			}
			store.UseEmbedding(f.models.Embedding, tag, settings.SimilarThreshold)
			if settings.Backfill.Enabled {
				backfillMemory = func(ctx context.Context) error {
					return store.Backfill(ctx, settings.Backfill.BatchSize)
				}
			}
		}
		memoryPrompt = func(ctx context.Context, user, _ string) (string, error) {
			return store.Prompt(ctx, user, settings)
		}
		memoryCapture = func(ctx context.Context, user, reply string, limit int) error {
			return store.Capture(ctx, f.models.Runtime, user, reply, limit)
		}
		extraTools = append(agent.CalendarTools(calendarEvents, confirms), agent.MemoryTool(store))
		memories := memory.NewHTTP(store, logger)
		previous := options.Business
		options.Business = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/api/v1/reminders/events" && streamHandler != nil {
				streamHandler.ServeHTTP(w, r)
				return
			}
			if calendar.OwnsPushPath(r.URL.Path) {
				pushHandler.ServeHTTP(w, r)
				return
			}
			if calendar.OwnsEventPath(r.URL.Path) || calendar.OwnsReminderPath(r.URL.Path) {
				events.ServeHTTP(w, r)
				return
			}
			if memory.OwnsPath(r.URL.Path) {
				memories.ServeHTTP(w, r)
				return
			}
			previous.ServeHTTP(w, r)
		})
	}
	if options.EnableP4 {
		f.chat, err = chat.OpenModule(f.ctx, chat.ModuleOptions{ExtraTools: extraTools, MemoryCapture: memoryCapture, MemoryPrompt: memoryPrompt, Pool: pool, Properties: properties, Models: f.models, Tasks: tasks, Idempotency: idem, Confirmations: confirms, Coordinator: f.coordinator, Gate: f.gate, Rate: limiter, Executor: f.executor, Transport: options.ModelTransport, Logger: logger, Enabled: !settings.Role.Worker()})
		if err != nil {
			return nil, err
		}
		metrics.chat = f.chat
		previous := options.Business
		options.Business = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if chat.OwnsPath(r.URL.Path) {
				f.chat.Handler.ServeHTTP(w, r)
				return
			}
			previous.ServeHTTP(w, r)
		})
	}

	var actuator http.Handler
	if !settings.Monitoring {
		actuator = newActuatorHandler(actuatorProperties, snapshot, metrics, logger)
	}
	f.handler = f.track(metrics.observeHTTP(newFoundationHandler(settings.Role, security, limiter, f.gate, f.health, options.Business, actuator, logger)))
	if settings.Monitoring {
		f.attachManagement(metrics, actuatorProperties, snapshot, logger)
	}
	if calendarEvents != nil && !settings.Role.Worker() {
		f.reminderStream = calendar.NewStream(f.ctx, calendarEvents, streamInterval)
		streamHandler = httpmiddleware.HandleErrors(f.reminderStream.Serve, logger)
	}
	if rabbitURI != "" {
		f.reminderBroadcast, err = calendar.StartBroadcast(f.ctx, rabbitURI, calendarEvents, f.reminderStream, f.coordinator, settings.Role.Allows("delivery"), logger)
		if err != nil {
			if f.reminderBroadcast != nil {
				f.reminderBroadcast.Close()
			}
			if f.reminderStream != nil {
				f.reminderStream.Close()
			}
			if f.chat != nil {
				_ = f.chat.Close(context.Background())
			}
			return nil, err
		}
	}
	if calendarEvents != nil {
		scanCtx, stop := context.WithCancel(f.ctx)
		var workers sync.WaitGroup
		f.stopReminders = func() {
			stop()
			workers.Wait()
			push.Close()
			if webhook != nil {
				webhook.Close()
			}
		}
		if scanSettings.ScanEnabled {
			workers.Go(func() {
				calendar.RunFixedDelay(scanCtx, time.Duration(scanSettings.ScanInitialDelayMS)*time.Millisecond,
					time.Duration(scanSettings.ScanIntervalMS)*time.Millisecond, func(ctx context.Context, now time.Time) {
						if _, err := calendarEvents.Scan(ctx, now); err != nil && ctx.Err() == nil {
							logger.ErrorContext(ctx, "提醒扫描失败，将在下一轮重试", "error", err)
						}
					})
			})
		}
		if settings.Role.Allows("delivery") {
			workers.Go(func() {
				calendar.RunFixedDelay(scanCtx, 15*time.Second, pushInterval, func(ctx context.Context, now time.Time) {
					if err := push.Dispatch(ctx, now); err != nil && ctx.Err() == nil {
						logger.ErrorContext(ctx, "Web Push 调度失败，将在下一轮重试")
					}
				})
			})
		}
		if webhook != nil && settings.Role.Allows("delivery") {
			workers.Go(func() {
				calendar.RunFixedDelay(scanCtx, 0, webhookInterval, func(ctx context.Context, _ time.Time) {
					if err := webhook.Dispatch(ctx); err != nil && ctx.Err() == nil {
						logger.ErrorContext(ctx, "提醒 Webhook 调度失败，将在下一轮重试")
					}
				})
			})
		}
	}
	if backfillMemory != nil {
		f.executor.Submit(f.ctx, backfillMemory)
	}
	return f, nil
}

func sessionCookie(shared bool) string {
	if shared {
		return "SESSION"
	}
	return "JSESSIONID"
}
func (f *Foundation) Handler() http.Handler            { return f.handler }
func (f *Foundation) Pool() *database.Pool             { return f.pool }
func (f *Foundation) ModelGate() *modelgate.Gate       { return f.gate }
func (f *Foundation) Executor() *taskexec.Executor     { return f.executor }
func (f *Foundation) History() migration.HistoryStatus { return f.history }
func (f *Foundation) Models() *model.Module            { return f.models }
func (f *Foundation) Chat() *chat.Module               { return f.chat }

func (f *Foundation) track(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		if f.closing {
			f.mu.Unlock()
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		f.active.Add(1)
		f.mu.Unlock()
		defer f.active.Done()
		ctx, cancel := context.WithCancel(r.Context())
		stop := context.AfterFunc(f.ctx, cancel)
		defer stop()
		defer cancel()
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// Close先拒新请求并排空HTTP，再排空执行器、释放协调租约、关闭自有池。
// 超时取消在途请求/任务并立即返回；清理仍等它们真正退出，不提前关闭正在使用的池。
// 调用方还应Shutdown监听器；可再次Close等待清理完成。
func (f *Foundation) Close(ctx context.Context) error {
	if ctx == nil {
		return errors.New("基础装配关闭context不能为空")
	}
	f.closeOnce.Do(func() {
		f.mu.Lock()
		f.closing = true
		f.mu.Unlock()
		go func() {
			if f.reminderBroadcast != nil {
				f.reminderBroadcast.Close()
			}
			if f.reminderStream != nil {
				f.reminderStream.Close()
			}
			if f.stopReminders != nil {
				f.stopReminders()
			}
			if f.chat != nil {
				_ = f.chat.Close(context.Background())
			}
			f.active.Wait()
			if f.modelTransport != nil {
				f.modelTransport.CloseIdleConnections()
			}
			f.executor.Shutdown()
			_ = f.executor.Wait(context.Background())
			f.closeErr = errors.Join(f.coordinator.Close(context.Background()), f.pool.Close())
			f.cancel()
			close(f.done)
		}()
	})
	select {
	case <-f.done:
		return f.closeErr
	case <-ctx.Done():
		f.cancel()
		f.executor.Stop()
		return ctx.Err()
	}
}
