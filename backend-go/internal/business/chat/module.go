package chat

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/confirmation"
	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	web "personal-ai-os/backend-go/internal/platform/http"
	"personal-ai-os/backend-go/internal/platform/modelgate"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

type ModuleOptions struct {
	ExtraTools    []agent.Definition
	MemoryCapture func(context.Context, string, string, int) error
	MemoryPrompt  func(context.Context, string, string) (string, error)
	Pool          *database.Pool
	Properties    *config.Properties
	Models        *model.Module
	Tasks         *task.Service
	Idempotency   *idempotency.Service
	Confirmations *confirmation.Service
	Coordinator   *coordination.Coordinator
	Gate          *modelgate.Gate
	Rate          web.ModelLimiter
	Executor      *taskexec.Executor
	Transport     http.RoundTripper
	Logger        *slog.Logger
	Enabled       bool
	Zone          *time.Location
	Remote        ObjectStore
}
type Module struct {
	Service     *Service
	Engine      *Engine
	Runs        *Runs
	Handler     http.Handler
	Attachments *Attachments
	Budget      *chatcontext.Budget
	schedule    chatSchedule
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	closeOnce   sync.Once
	closeDone   chan struct{}
	remoteClose func() error
}

func CheckPrivileges(ctx context.Context, pool *database.Pool) error {
	return pool.WithConn(ctx, func(c *sql.Conn) error {
		tables := []string{"chat_session", "chat_message", "chat_message_citation", "chat_context_preference", "agent_plan", "agent_plan_step", "chat_run", "chat_run_event", "agent_question", "agent_followup"}
		for _, table := range tables {
			var ok bool
			if e := c.QueryRowContext(ctx, `SELECT has_table_privilege(current_user,$1,'SELECT') AND has_table_privilege(current_user,$1,'INSERT') AND has_table_privilege(current_user,$1,'UPDATE') AND has_table_privilege(current_user,$1,'DELETE')`, "public."+table).Scan(&ok); e != nil {
				return e
			}
			if !ok {
				return errors.New("P4运行账号缺少会话、计划、运行或交互权限")
			}
		}
		for _, table := range []string{"token_usage", "operation_record", "confirmation"} {
			var ok bool
			if e := c.QueryRowContext(ctx, `SELECT has_table_privilege(current_user,$1,'SELECT')`, "public."+table).Scan(&ok); e != nil {
				return e
			}
			if !ok {
				return errors.New("P4运行账号缺少用量或审计读取权限")
			}
		}
		var ok bool
		if e := c.QueryRowContext(ctx, `SELECT has_table_privilege(current_user,'public.token_usage','INSERT') AND has_sequence_privilege(current_user,'public.chat_message_id_seq','USAGE') AND has_sequence_privilege(current_user,'public.chat_message_citation_id_seq','USAGE') AND has_sequence_privilege(current_user,'public.chat_run_event_id_seq','USAGE') AND has_sequence_privilege(current_user,'public.agent_followup_seq_seq','USAGE') AND has_sequence_privilege(current_user,'public.token_usage_id_seq','USAGE')`).Scan(&ok); e != nil {
			return e
		}
		if !ok {
			return errors.New("P4运行账号缺少用量写入或序列权限")
		}
		return nil
	})
}
func OpenModule(parent context.Context, o ModuleOptions) (m *Module, err error) {
	if parent == nil || o.Pool == nil || o.Properties == nil || o.Models == nil || o.Tasks == nil || o.Idempotency == nil || o.Confirmations == nil || o.Coordinator == nil || o.Gate == nil || o.Rate == nil || o.Executor == nil || o.Logger == nil {
		return nil, errors.New("P4模块依赖不完整")
	}
	if o.Zone == nil {
		o.Zone = time.Local
	}
	if err = CheckPrivileges(parent, o.Pool); err != nil {
		return nil, err
	}
	// 只绑定当前阶段消费的已知属性，避免解析未来阶段的秘密或连接目标。
	values := map[string]string{}
	for _, key := range []string{"app.ai.system-prompt", "app.ai.memory.max-messages", "app.ai.memory.context-window-tokens", "app.context.summary.enabled", "app.context.summary.min-messages", "app.context.summary.max-excerpt-chars", "app.context.summary.max-summary-chars", "app.context.summary.timeout", "app.context.auto-memory.enabled", "app.context.auto-memory.max-items", "app.context.protection.output-reserve-tokens", "app.context.protection.safety-margin-tokens", "app.context.protection.image-reserve-tokens", "app.context.protection.max-retries", "app.plan.enabled", "app.plan.max-inject-chars", "app.agent.max-model-calls", "app.agent.max-consecutive-failures", "app.chat.upload-dir", "app.chat.max-attachment-bytes", "app.chat.stream-timeout"} {
		v, found, e := o.Properties.Get(key)
		if e != nil {
			return nil, e
		}
		if found {
			values[key] = v
		}
	}
	ai, e := config.BindAIProperties(values)
	if e != nil {
		return nil, e
	}
	cp, e := config.BindContextProperties(values)
	if e != nil {
		return nil, e
	}
	protection, e := config.BindContextProtectionProperties(values)
	if e != nil {
		return nil, e
	}
	plan, e := config.BindPlanProperties(values)
	if e != nil {
		return nil, e
	}
	execution, e := config.BindAgentExecutionProperties(values)
	if e != nil {
		return nil, e
	}
	timeout, e := config.ParseDuration(values["app.chat.stream-timeout"])
	if e != nil || timeout == nil || *timeout <= 0 || *timeout > 24*time.Hour {
		return nil, errors.New("对话流超时配置无效")
	}
	maximum, e := strconv.ParseInt(values["app.chat.max-attachment-bytes"], 10, 64)
	if e != nil {
		return nil, errors.New("附件大小配置无效")
	}
	schedule, e := bindChatSchedule(o.Properties)
	if e != nil {
		return nil, e
	}
	remote := o.Remote
	var closeRemote func() error
	defer func() {
		if err != nil && closeRemote != nil {
			_ = closeRemote()
		}
	}()
	if remote == nil {
		remote, e = OpenS3(o.Properties, o.Transport, maximum)
		if e != nil {
			return nil, e
		}
	}
	if o.Remote == nil {
		if owned, ok := remote.(*S3Store); ok {
			closeRemote = owned.Close
		}
	}
	files, e := NewAttachments(values["app.chat.upload-dir"], maximum, o.Pool, remote)
	if e != nil {
		return nil, e
	}
	files.ExtractPDF = ExtractPDF
	store, e := NewPostgresStore(o.Pool)
	if e != nil {
		return nil, e
	}
	store.attachments = files
	service, e := NewService(store, files.Cleanup)
	if e != nil {
		return nil, e
	}
	plans := &agent.Plans{Pool: o.Pool, Properties: plan}
	interactions := &Interactions{Pool: o.Pool}
	requests, e := task.NewConfirmationRequests(o.Tasks, o.Confirmations)
	if e != nil {
		return nil, e
	}
	definitions := agent.TaskTools(o.Tasks, requests)
	definitions = append(definitions, plans.Tools()...)
	definitions = append(definitions, interactions.Tool())
	definitions = append(definitions, o.ExtraTools...)
	registry, e := agent.NewRegistry(o.Idempotency, plans, definitions)
	if e != nil {
		return nil, e
	}
	assembler := &Assembler{Service: service, Tools: registry.Definitions, Contributors: []PromptContributor{CurrentTimeContributor(o.Zone, time.Now), {Order: 10, Contribute: func(ctx context.Context, _ string, id string) (string, error) { return plans.Prompt(ctx, id) }}, {Order: 2147483647, Contribute: func(context.Context, string, string) (string, error) { return toolResultConstraint, nil }}}}
	if o.MemoryPrompt != nil {
		assembler.Contributors = append(assembler.Contributors, PromptContributor{Contribute: o.MemoryPrompt})
	}

	budget, e := chatcontext.NewBudget(parent, ai, protection, chatcontext.PostgresPreferences{Pool: o.Pool}, o.Models.Service.LookupWindow, func() string {
		if c := o.Models.Runtime.Current(); c != nil {
			return c.Name()
		}
		return ""
	}, o.Models.Runtime.Evidence, assembler.FixedTokens)
	if e != nil {
		return nil, e
	}
	assembler.Budget = budget
	history := &History{Store: store, Budget: budget}
	assembler.Summary = &Summary{Store: store, History: history, Properties: cp.Summary, Model: func(ctx context.Context, r model.ChatRequest) (model.ChatResult, error) {
		c, epoch, e := o.Models.Runtime.TargetSnapshot(ctx, "")
		if e != nil {
			return model.ChatResult{}, e
		}
		return budget.GuardStream(ctx, c, r, epoch, nil, func(model.ChatChunk) error { return nil })
	}}
	usage := &UsageService{Pool: o.Pool, Zone: o.Zone, Now: time.Now, Logger: o.Logger}
	engine := &Engine{Service: service, Runtime: o.Models.Runtime, Budget: budget, History: history, Assembler: assembler, Registry: registry, Plans: plans, Attachments: files, Usage: usage, Executor: o.Executor, Agent: execution, Timeout: *timeout, Logger: o.Logger}
	if o.MemoryCapture != nil && cp.AutoMemory.Enabled {
		engine.PostTurn = append(engine.PostTurn, func(ctx context.Context, _ string, user, reply string) error {
			return o.MemoryCapture(ctx, user, reply, cp.AutoMemory.MaxItems)
		})
	}
	ctx, cancel := context.WithCancel(parent)
	m = &Module{Service: service, Engine: engine, Attachments: files, Budget: budget, schedule: schedule, cancel: cancel, closeDone: make(chan struct{}), remoteClose: closeRemote}
	m.Runs = NewRuns(ctx, o.Pool, engine, interactions, o.Coordinator, o.Gate, o.Rate, o.Enabled)
	storage := &HTTP{service: service, zone: o.Zone}
	h := &ModuleHTTP{module: m, storage: storage, audit: &agent.Audit{Pool: o.Pool}, coordinator: o.Coordinator}
	m.Handler = web.HandleErrors(h.serve, o.Logger)
	if o.Enabled {
		if e = m.Runs.Recover(parent); e != nil {
			cancel()
			_ = m.Runs.Close(context.Background())
			return nil, e
		}
		m.Runs.StartScheduler()
		if schedule.janitor && !schedule.cron.disabled {
			m.wg.Add(1)
			go func() {
				defer m.wg.Done()
				// A single non-overlapping sweep in the configured local cron second.
				timer := time.NewTimer(time.Until(time.Now().Truncate(time.Second).Add(time.Second)))
				defer timer.Stop()
				select {
				case <-ctx.Done():
					return
				case now := <-timer.C:
					ticks := time.NewTicker(time.Second)
					defer ticks.Stop()
					prune := func(parent context.Context, now time.Time) {
						prune, cancel := context.WithTimeout(parent, time.Minute)
						defer cancel()
						if _, err := files.Prune(prune, now); err != nil && parent.Err() == nil {
							o.Logger.Warn("聊天附件清理未完成，下次重试")
						}
					}
					if schedule.cron.matches(now.In(o.Zone)) {
						prune(ctx, now)
					}
					schedule.runJanitor(ctx, o.Zone, ticks.C, prune)
				}
			}()
		}
	}
	return m, nil
}
func (m *Module) Close(ctx context.Context) error {
	m.closeOnce.Do(func() {
		m.cancel()
		go func() {
			_ = m.Runs.Close(context.Background())
			m.wg.Wait()
			if m.remoteClose != nil {
				_ = m.remoteClose()
			}
			close(m.closeDone)
		}()
	})
	select {
	case <-m.closeDone:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func OwnsPath(path string) bool {
	return strings.HasPrefix(path, "/api/v1/chat/sessions") || path == "/api/v1/chat/context" || path == "/api/v1/chat/context-budget" || strings.HasPrefix(path, "/api/v1/chat/uploads") || path == "/api/v1/usage" || strings.HasPrefix(path, "/api/v1/conversations/") && strings.HasSuffix(path, "/plan") || path == "/api/v1/agent/tool-invocations"
}
