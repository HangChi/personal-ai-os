package app

import (
	"context"
	"database/sql/driver"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/mcp"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/business/workflow"
	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/health"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/maintenance"
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
	EnableP6                bool
	EnableP7                bool
	EnableP8                bool
	ContainerNetwork        bool
	CandidateRedis          *redisclient.Client
	EmbeddingRedis          *redisclient.Client
	ModelTransport          http.RoundTripper
}

func (FoundationOptions) String() string               { return "FoundationOptions{redacted}" }
func (o FoundationOptions) GoString() string           { return o.String() }
func (FoundationOptions) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

// Foundation是尚未发布的P1组合入口，不启动业务Worker，也不宣告迁移就绪。
type Foundation struct {
	pool             *database.Pool
	coordinator      *coordination.Coordinator
	gate             *modelgate.Gate
	executor         *taskexec.Executor
	health           *health.Service
	history          migration.HistoryStatus
	handler          http.Handler
	management       http.Handler
	ctx              context.Context
	cancel           context.CancelFunc
	mu               sync.Mutex
	closing          bool
	served           bool
	active           sync.WaitGroup
	closeOnce        sync.Once
	done             chan struct{}
	closeErr         error
	models           *model.Module
	chat             *chat.Module
	modelTransport   *http.Transport
	p5               *foundationP5
	p6               *knowledge.Module
	mcp              *mcp.Registry
	retention        *maintenance.Worker
	workflowStop     func()
	containerNetwork bool
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
	if options.EnableP5 && !options.EnableP4 {
		return nil, errors.New("P5需要启用P4")
	}
	if options.EnableP6 && !options.EnableP5 {
		return nil, errors.New("P6需要启用P5")
	}
	if options.EnableP7 && !options.EnableP6 {
		return nil, errors.New("P7需要启用P6")
	}
	if options.EnableP8 && !options.EnableP7 {
		return nil, errors.New("P8需要启用P7")
	}
	var retention config.RetentionProperties
	if options.EnableP8 {
		retention, err = properties.Retention()
		if err != nil {
			return nil, err
		}
		if _, err = maintenance.ParseCron(retention.Cron); err != nil {
			return nil, err
		}
	}
	var personalMCP mcp.PersonalOptions
	if options.EnableP7 {
		personalMCP, err = mcp.PersonalOptionsFrom(properties)
		if err != nil {
			return nil, err
		}
	}
	if options.CandidateRedis != nil {
		if !options.EnableP6 || options.CandidateRedis == options.SessionRedis || options.CandidateRedis == options.RateRedis || options.CandidateRedis == options.EmbeddingRedis {
			return nil, errors.New("P6候选缓存必须使用独立专用Redis客户端")
		}
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
	if options.ContainerNetwork && (!options.EnableP8 || !settings.Auth.Enabled || !settings.Security.CSRFEnabled) {
		return nil, errors.New("P8容器网络必须启用全部模块、认证与CSRF")
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
	security, err := auth.NewHTTP(service, sessions, auth.HTTPOptions{CSRFEnabled: settings.Security.CSRFEnabled, CookieSecure: settings.Security.CookieSecure, Cookie: &sessionProperties.Cookie, MCPPath: personalMCP.Endpoint, Multipart: httpmiddleware.MultipartLimits{MaxFileBytes: multipart.MaxFileBytes, MaxRequestBytes: multipart.MaxRequestBytes, TempDirectory: multipart.Location}}, logger)
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
	f := &Foundation{pool: pool, done: make(chan struct{}), containerNetwork: options.ContainerNetwork}
	f.ctx, f.cancel = context.WithCancel(context.Background())
	defer func() {
		if err != nil {
			f.cancel()
			if f.retention != nil {
				f.retention.Close()
			}
			if f.mcp != nil {
				f.mcp.Close()
			}
			if f.chat != nil {
				_ = f.chat.Close(context.Background())
			}
			if f.p6 != nil {
				f.p6.Close()
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
	metrics := &foundationMetrics{extra: options.Observer, started: time.Now(), httpEnabled: actuatorProperties.HTTPRequests, httpHistogram: actuatorProperties.HTTPHistogram, embeddingHistogram: actuatorProperties.EmbeddingHistogram,
		knowledgeRecallHistogram: actuatorProperties.KnowledgeRecallHistogram, knowledgeRagHistogram: actuatorProperties.KnowledgeRagHistogram}
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
	var p5 *foundationP5
	if options.EnableP5 {
		p5, err = newFoundationP5(properties, pool, settings.Role)
		if err != nil {
			return nil, err
		}
	}
	var tasks *task.Service
	if options.EnableP7 {
		mcpOptions, e := mcp.OptionsFrom(properties)
		if e != nil {
			return nil, e
		}
		f.mcp, err = mcp.OpenRegistry(f.ctx, mcpOptions, logger)
		if err != nil {
			return nil, err
		}
	}
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
		if p5 != nil {
			actions = append(actions, p5.events.Actions()...)
			handlers = append(handlers, p5.events)
		}
		if options.EnableP7 {
			h := &mcp.ConfirmationHandler{Registry: f.mcp}
			actions = append(actions, h.Actions()...)
			handlers = append(handlers, h)
		}
		registry, taskErr := confirmation.NewRegistryFor(actions, handlers...)
		if options.EnableP7 {
			registry, taskErr = confirmation.NewRegistry(handlers...)
		}
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
	if p5 != nil {
		if err := p5.configure(ctx, f, properties, logger); err != nil {
			return nil, err
		}
		options.Business = p5.route(options.Business)
	}
	if options.EnableP6 {
		background, e := modelgate.NewShared(settings.ModelConcurrency, config.Index, f.coordinator)
		if e != nil {
			return nil, e
		}
		acquire := func(ctx context.Context) (func(), error) {
			lease, e := background.TryAcquire(ctx)
			if e != nil || lease == nil {
				return nil, e
			}
			release := func() {
				cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				_ = lease.Release(cleanup)
			}
			ok, e := limiter.TryAcquire(ctx)
			if e != nil || !ok {
				release()
				return nil, e
			}
			return release, nil
		}
		var cache knowledge.RedisCacheClient
		if options.CandidateRedis != nil {
			cache = options.CandidateRedis
		}
		transport := options.ModelTransport
		if transport == nil {
			transport = f.modelTransport
		}
		f.p6, err = knowledge.OpenModule(f.ctx, knowledge.ModuleOptions{Pool: pool, Properties: properties, Models: f.models, Coordinator: f.coordinator, Role: settings.Role, Acquire: acquire, CandidateRedis: cache, Transport: transport, Logger: logger})
		if err != nil {
			return nil, err
		}
		metrics.p6 = f.p6
		previous := options.Business
		options.Business = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if knowledge.OwnsPath(r.URL.Path) {
				f.p6.Handler.ServeHTTP(w, r)
				return
			}
			previous.ServeHTTP(w, r)
		})
	}
	if options.EnableP4 {
		chatOptions := chat.ModuleOptions{Pool: pool, Properties: properties, Models: f.models, Tasks: tasks, Idempotency: idem,
			Confirmations: confirms, Coordinator: f.coordinator, Gate: f.gate, Rate: limiter, Executor: f.executor,
			Transport: options.ModelTransport, Logger: logger, Enabled: !settings.Role.Worker()}
		key, _, keyErr := properties.Get("BRAVE_SEARCH_API_KEY")
		if keyErr != nil {
			return nil, keyErr
		}
		if key = strings.TrimSpace(key); key != "" {
			chatOptions.SearchWeb = agent.NewBraveSearch(key)
			chatOptions.ExtraTools = append(chatOptions.ExtraTools, agent.WebSearchTool(chatOptions.SearchWeb))
		}
		if p5 != nil {
			p5.addChatOptions(&chatOptions)
		}
		if f.mcp != nil {
			chatOptions.ExtraTools = append(chatOptions.ExtraTools, agent.MCPBindings(f.mcp, confirms)...)
		}
		if f.p6 != nil {
			chatOptions.ExtraTools = append(chatOptions.ExtraTools, agent.KnowledgeTool(f.p6.Pipeline))
			chatOptions.RetrieveEvidence = func(ctx context.Context, q string, limit int) (chat.RetrievedEvidence, error) {
				r, e := f.p6.Pipeline.Retrieve(ctx, q, &limit)
				if e != nil {
					return chat.RetrievedEvidence{}, e
				}
				citations := []chat.Citation{}
				for i, h := range r.Hits {
					title, file := h.DocumentTitle, h.Filename
					citations = append(citations, chat.Citation{Index: i + 1, DocumentID: h.DocumentID, DocumentTitle: &title, Filename: &file, ChunkIndex: h.ChunkIndex, Content: h.Content})
				}
				return chat.RetrievedEvidence{Citations: citations, Retrieval: r, Emit: len(r.Stages) > 0 || r.Guarded(), Guarded: r.Guarded(), Answer: r.GuardedAnswer()}, nil
			}
		}
		f.chat, err = chat.OpenModule(f.ctx, chatOptions)
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

	if options.EnableP7 {
		options.Business = f.p7Route(tasks, p5, personalMCP, name, version, logger, options.Business)
	}
	var workflows *workflow.Service
	if options.EnableP8 {
		workflows = &workflow.Service{Pool: pool, Calendar: p5.events}
		previous := options.Business
		handler := workflows.Handler(logger)
		options.Business = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if workflow.OwnsPath(r.URL.Path) {
				handler.ServeHTTP(w, r)
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
	if p5 != nil {
		if err := p5.start(f, logger); err != nil {
			p5.close()
			_ = f.chat.Close(context.Background())
			return nil, err
		}
		f.p5 = p5
		metrics.p5 = p5
	}
	if options.EnableP8 {
		f.retention, err = maintenance.Start(f.ctx, retention, maintenance.NewPostgresStore(f.pool), logger)
		if err != nil {
			return nil, err
		}
	}
	if workflows != nil && settings.Role.Allows("review") {
		value, found, e := properties.Get("app.workflows.enabled")
		if e != nil {
			return nil, e
		}
		if !found || value == "true" {
			f.workflowStop = workflows.Start(f.ctx, logger)
		} else if value != "false" {
			return nil, errors.New("app.workflows.enabled必须为true或false")
		}
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
func (f *Foundation) Knowledge() *knowledge.Module     { return f.p6 }

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
			if f.workflowStop != nil {
				f.workflowStop()
			}
			if f.retention != nil {
				f.retention.Close()
			}
			if f.p5 != nil {
				f.p5.close()
			}
			if f.chat != nil {
				_ = f.chat.Close(context.Background())
			}
			f.active.Wait()
			if f.mcp != nil {
				f.mcp.Close()
			}
			if f.p6 != nil {
				f.p6.Close()
			}
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
