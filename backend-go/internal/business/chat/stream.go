package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

type Publish func(string, any) error
type PreparedTurn struct {
	Client        *model.ChatClient
	Epoch         uint64
	Session       Session
	Request       StreamRequest
	clientContext context.Context
}
type RetrievedEvidence struct {
	Citations []Citation
	Retrieval any
	Emit      bool
	Guarded   bool
	Answer    string
}
type Engine struct {
	Service          *Service
	Runtime          *model.Runtime
	Budget           *chatcontext.Budget
	History          *History
	Assembler        *Assembler
	Registry         *agent.Registry
	Plans            *agent.Plans
	Attachments      *Attachments
	Usage            *UsageService
	Executor         *taskexec.Executor
	Agent            config.AgentExecutionProperties
	Timeout          time.Duration
	Retrieve         func(context.Context, string, int) ([]Citation, error)
	RetrieveEvidence func(context.Context, string, int) (RetrievedEvidence, error)
	PostTurn         []func(context.Context, string, string, string) error
	Metrics          StreamMetrics
	Logger           *slog.Logger
}
type StreamMetrics struct {
	Measurements                       StreamMeasurements
	Active                             atomic.Int64
	Opened, Completed, Failed, Yielded atomic.Uint64
	DurationNanos, FirstNanos          atomic.Uint64
	Prompt, Completion                 atomic.Uint64
}

func (e *Engine) Prepare(ctx context.Context, id string, request StreamRequest) (p PreparedTurn, err error) {
	if err = request.Validate(); err != nil {
		return
	}
	p.Session, err = e.Service.Require(ctx, id)
	if err != nil {
		return
	}
	p.Client, p.Epoch, err = e.Runtime.TargetSnapshot(ctx, value(p.Session.ChatModel))
	if err != nil {
		return
	}
	p.Request = request
	return
}

var calendarContext = regexp.MustCompile(`(?i)日程|日历|会议|组会|开会|大课|教室|明天|后天|上午|下午|晚上|[0-2]?[0-9][：:][0-9]{2}|calendar|schedule|meeting`)
var calendarQuery = regexp.MustCompile(`查看|查询|列出|显示|看看|有哪些|有没有|是否|了吗|了没`)
var calendarWords = regexp.MustCompile(`日程|日历|会议|组会|开会|大课|教室`)
var createdClaim = regexp.MustCompile(`(?im)^\s*(?:[-*>#]\s*)?(?:(?:好的|好|日程|会议)[，,:：\s]*)?[\s*✅]*(?:(?:已(?:经)?(?:成功)?|成功)[\s*✅]*(?:(?:为你|为您|帮你|帮您|给你)[\s*]*)?(?:创建|添加|新增|安排|登记|录入)|(?:created|added|scheduled)\s+(?:the\s+|your\s+)?(?:event|meeting))`)

func holdCalendar(text string) bool {
	return !calendarQuery.MatchString(text) && calendarContext.MatchString(text) && (!(strings.Contains(text, "任务") || strings.Contains(text, "待办")) || calendarWords.MatchString(text))
}

const calendarRepair = "[系统执行校验]\n本轮没有成功的 createCalendarEvent 工具记录，不能声称已创建日程。先核对用户原始意图：仅查询、核实或总结已有日程时，基于查询结果说明记录是否存在，禁止新增。用户确实要求新增或继续安排时，结合原始请求和会话前文确定日期、时间及地点，信息齐全则调用 createCalendarEvent；若信息不足先询问，不能直接输出已创建。不得重复执行本轮已经成功的其他写操作。"
const calendarNotCreated = "这项日程尚未创建：本轮没有获得创建日程工具的成功结果。请重试，或在日历中手动添加。"

func (e *Engine) Run(ctx context.Context, p PreparedTurn, turnID, recovery string, checkpoint func(context.Context) error, publish Publish) (runErr error) {
	timeout := e.Timeout
	if timeout <= 0 {
		timeout = 120 * time.Second
	}
	if ThinkingBudget(value(p.Request.ThinkingLevel)) == 8192 {
		timeout *= 2
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	started := time.Now()
	e.Metrics.Active.Add(1)
	e.Metrics.Opened.Add(1)
	defer func() {
		e.Metrics.Active.Add(-1)
		e.Metrics.DurationNanos.Add(uint64(time.Since(started)))
		outcome := "success"
		if runErr != nil {
			switch {
			case errors.Is(runErr, agent.ErrYield):
				outcome = "yielded"
			case errors.Is(runErr, context.DeadlineExceeded):
				outcome = "timeout"
			case errors.Is(runErr, context.Canceled):
				outcome = "cancelled"
			default:
				outcome = "error"
			}
		}
		e.Metrics.Measurements.Duration(p.Client.Provider(), p.Client.Name(), outcome, time.Since(started))
		if runErr != nil {
			if errors.Is(runErr, agent.ErrYield) {
				e.Metrics.Yielded.Add(1)
			} else {
				e.Metrics.Failed.Add(1)
			}
		} else {
			e.Metrics.Completed.Add(1)
		}
	}()
	var full strings.Builder
	userSaved := false
	first := false
	var hits []Citation
	request := p.Request
	send := func(name string, v any) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		return publish(name, v)
	}
	defer func() {
		if recover() != nil {
			runErr = errors.New("对话执行异常")
		}
		if runErr == nil {
			return
		}
		// The legacy Java HTTP stream cancels without appending an assistant message.
		// Durable runs have no clientContext and retain their interrupted checkpoint.
		if p.clientContext != nil && p.clientContext.Err() != nil {
			return
		}
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if userSaved {
			body := strip(full.String())
			if errors.Is(runErr, agent.ErrYield) {
				if body == "" {
					body = "已保存进度，等待用户输入或应用补充要求。"
				}
			} else {
				marker := "（本条回答因连接中断未说完）"
				var b *api.BusinessError
				if errors.As(runErr, &b) && b.Code() == api.AgentExecutionLimit {
					marker = "（" + api.AgentExecutionLimit.Message() + "）"
				}
				if body != "" {
					body += "\n\n"
				}
				body += marker
			}
			_ = e.Service.Append(cleanup, p.Session.ConversationID, []NewMessage{{Role: "ASSISTANT", Content: body}})
		}
		if errors.Is(runErr, agent.ErrYield) {
			_ = publish("yield", map[string]string{"message": "已保存进度，正在等待输入或应用引导"})
		} else {
			code := api.AIUpstreamError
			if errors.Is(runErr, context.DeadlineExceeded) {
				code = api.AITimeout
			}
			var b *api.BusinessError
			if errors.As(runErr, &b) {
				code = b.Code()
			}
			_ = publish("error", map[string]any{"code": code, "message": code.Message()})
		}
	}()
	if err := send("start", map[string]string{"conversationId": p.Session.ConversationID}); err != nil {
		return err
	}
	if err := e.Service.InitializeTitle(ctx, p.Session.ConversationID, &request.Content); err != nil {
		return err
	}
	if request.Knowledge() {
		evidence, err := e.retrieveForPrompt(ctx, request.Content, request.Limit(), send)
		if err != nil {
			return err
		}
		hits = evidence.Citations
		if hits == nil {
			hits = []Citation{}
		}
		if err = send("citations", map[string]any{"count": len(hits), "citations": hits}); err != nil {
			return err
		}
		if evidence.Emit {
			if err = send("rag", evidence.Retrieval); err != nil {
				return err
			}
		}
		if evidence.Guarded {
			attachments, err := e.Attachments.Build(ctx, request.Attachments)
			if err != nil {
				return err
			}
			if checkpoint != nil {
				if err = checkpoint(ctx); err != nil {
					return err
				}
			}
			if err = e.Service.Append(ctx, p.Session.ConversationID, []NewMessage{{Role: "USER", Content: request.Content, Attachments: attachments.Verified}}); err != nil {
				return err
			}
			userSaved = true
			full.WriteString(evidence.Answer)
			if err = e.Service.Complete(ctx, p.Session.ConversationID, evidence.Answer, nil); err != nil {
				return err
			}
			userSaved = false
			if err = e.Service.AttachCitations(ctx, p.Session.ConversationID, hits); err != nil && e.Logger != nil {
				e.Logger.WarnContext(ctx, "引用挂载失败，不影响本轮回答")
			}
			if err = send("delta", map[string]string{"content": evidence.Answer}); err != nil {
				return err
			}
			return send("done", map[string]any{"conversationId": p.Session.ConversationID, "content": evidence.Answer, "promptTokens": 0})
		}
	}
	attachments, err := e.Attachments.Build(ctx, request.Attachments)
	if err != nil {
		return err
	}
	system, err := e.Assembler.Prepare(ctx, hits, request.Content, p.Session.ConversationID, attachments.Text, len(attachments.Images), value(p.Session.ChatModel), ThinkingBudget(value(request.ThinkingLevel)))
	if err != nil {
		return err
	}
	system += recovery
	history, err := e.History.ModelMessages(ctx, p.Session.ConversationID)
	if err != nil {
		return err
	}
	messages := []model.Message{{Role: "system", Content: system}}
	messages = append(messages, history...)
	messages = append(messages, model.Message{Role: "user", Content: request.Content, Images: attachments.Images})
	recent, err := e.Assembler.recent(ctx, p.Session.ConversationID)
	if err != nil {
		return err
	}
	estimatedPromptChars := chatcontext.JavaLength(system) + chatcontext.JavaLength(request.Content) + e.Assembler.ToolChars()
	for _, m := range recent {
		estimatedPromptChars += chatcontext.JavaLength(m.Content)
	}
	if err = e.Service.Append(ctx, p.Session.ConversationID, []NewMessage{{Role: "USER", Content: request.Content, Attachments: attachments.Verified}}); err != nil {
		return err
	}
	userSaved = true
	turn := &agent.Turn{ConversationID: p.Session.ConversationID, ID: turnID, Budget: agent.NewTurnBudget(e.Agent), Checkpoint: checkpoint, Progress: func(a agent.Activity) { _ = send("activity", a) }}
	extra := map[string]any{"enable_thinking": ThinkingBudget(value(request.ThinkingLevel)) > 0}
	if n := ThinkingBudget(value(request.ThinkingLevel)); n > 0 {
		extra["thinking_budget"] = n
	}
	req := model.ChatRequest{Messages: messages, Tools: e.Registry.Definitions(), ExtraBody: extra}
	var actual *model.Usage
	actualModel := ""
	repaired := false
	planCorrection := false
	hold := holdCalendar(request.Content)
	var previousPlan string
	if e.Plans != nil {
		previousPlan, err = e.Plans.Prompt(ctx, p.Session.ConversationID)
		if err != nil {
			return err
		}
	}
	for {
		activePlan := false
		if e.Plans != nil {
			current, planErr := e.Plans.Get(ctx, p.Session.ConversationID, true)
			if planErr != nil {
				return planErr
			}
			activePlan = current != nil
			latest, planErr := e.Plans.Prompt(ctx, p.Session.ConversationID)
			if planErr != nil {
				return planErr
			}
			if previousPlan != "" {
				req.Messages[0].Content = strings.Replace(req.Messages[0].Content, previousPlan, latest, 1)
			} else if latest != "" {
				req.Messages[0].Content += "\n" + latest
			}
			previousPlan = latest
		}
		var held strings.Builder
		before := func() error {
			if err := turn.Check(ctx); err != nil {
				return err
			}
			return turn.Budget.BeforeModel()
		}
		sawUsage := false
		result, err := e.Budget.GuardStream(ctx, p.Client, req, p.Epoch, before, func(chunk model.ChatChunk) error {
			if err := turn.Check(ctx); err != nil {
				return err
			}
			if chunk.Usage != nil && chunk.Usage.PromptTokens >= 0 && chunk.Usage.CompletionTokens >= 0 && (chunk.Usage.PromptTokens > 0 || chunk.Usage.CompletionTokens > 0) {
				v := *chunk.Usage
				actual = &v
				sawUsage = true
			}
			if chunk.Content != "" {
				if full.Len()+held.Len()+len(chunk.Content) > 8<<20 {
					return api.NewBusinessError(api.AIUpstreamError, "模型输出超过安全上限")
				}
				if hold || activePlan {
					held.WriteString(chunk.Content)
					return nil
				}
				if !first {
					first = true
					e.Metrics.FirstNanos.Add(uint64(time.Since(started)))
					e.Metrics.Measurements.First(p.Client.Provider(), p.Client.Name(), time.Since(started))
				}
				full.WriteString(chunk.Content)
				return send("delta", map[string]string{"content": chunk.Content})
			}
			return nil
		})
		if err != nil {
			return err
		}
		if sawUsage {
			actualModel = result.Model
		}
		if len(result.ToolCalls) > 0 {
			planCorrection = false
			for i := range result.ToolCalls {
				if result.ToolCalls[i].ID == "" {
					result.ToolCalls[i].ID = agent.UUID()
				}
			}
			req.Messages = append(req.Messages, model.Message{Role: "assistant", Content: result.Content, ToolCalls: result.ToolCalls})
			for _, call := range result.ToolCalls {
				r, err := e.Registry.Execute(ctx, turn, call)
				if err != nil {
					return err
				}
				raw, err := json.Marshal(r)
				if err != nil {
					return err
				}
				req.Messages = append(req.Messages, model.Message{Role: "tool", ToolCallID: call.ID, Name: call.Function.Name, Content: string(raw)})
			}
			continue
		}
		if e.Plans != nil {
			active, err := e.Plans.Get(ctx, p.Session.ConversationID, true)
			if err != nil {
				return err
			}
			if active != nil {
				if planCorrection {
					return agent.ErrYield
				}
				planCorrection = true
				req.Messages = append(req.Messages, model.Message{Role: "assistant", Content: result.Content}, model.Message{Role: "system", Content: "当前执行计划尚有未完成步骤，不能宣称任务已完成。请核对计划和工具回执，按序完成剩余步骤；需要等待用户输入时调用 askUser。"})
				continue
			}
		}
		if hold || activePlan {
			if hold && len(result.ToolCalls) == 0 && !turn.Budget.HasWrite("createCalendarEvent") && createdClaim.MatchString(held.String()) {
				if !repaired {
					repaired = true
					req.Messages = append(req.Messages, model.Message{Role: "system", Content: calendarRepair})
					continue
				}
				held.Reset()
				held.WriteString(calendarNotCreated)
				result.Content = calendarNotCreated
			}
			if held.Len() > 0 {
				if !first {
					first = true
					e.Metrics.FirstNanos.Add(uint64(time.Since(started)))
					e.Metrics.Measurements.First(p.Client.Provider(), p.Client.Name(), time.Since(started))
				}
				full.WriteString(held.String())
				if err = send("delta", map[string]string{"content": held.String()}); err != nil {
					return err
				}
			}
		}
		break
	}
	if err = turn.Check(ctx); err != nil {
		return err
	}
	reply := full.String()
	record := resolveUsage(actual, actualModel, request.Content, estimatedPromptChars, reply)
	if err = e.Service.Complete(ctx, p.Session.ConversationID, reply, nil); err != nil {
		return err
	}
	// 原 Java 引用挂载失败只记日志；完成前尝试挂载，但不让展示快照破坏回答。
	if len(hits) > 0 {
		if err = e.Service.AttachCitations(ctx, p.Session.ConversationID, hits); err != nil && e.Logger != nil {
			e.Logger.WarnContext(ctx, "引用挂载失败，不影响本轮回答")
		}
	}
	userSaved = false
	if err = send("done", map[string]any{"conversationId": p.Session.ConversationID, "content": reply, "promptTokens": record.Prompt}); err != nil {
		return err
	}
	e.Metrics.Measurements.Usage(record)
	e.Metrics.Prompt.Add(uint64(record.Prompt))
	e.Metrics.Completion.Add(uint64(record.Completion))
	if e.Usage != nil {
		e.Usage.Record(ctx, e.Executor, p.Session.ConversationID, record)
	}
	if e.Executor != nil {
		e.Executor.Submit(ctx, func(post context.Context) error {
			if e.Assembler.Summary != nil {
				_, _ = e.Assembler.Summary.Refresh(post, p.Session.ConversationID, nil)
			}
			for _, hook := range e.PostTurn {
				_ = hook(post, p.Session.ConversationID, request.Content, reply)
			}
			return nil
		})
	}
	return nil
}

// sseWriter 的写入和心跳共用锁，只在处理器存活时写 ResponseWriter。
type sseWriter struct {
	mu     sync.Mutex
	writer http.ResponseWriter
	closed bool
}

func (s *sseWriter) event(id int64, name string, value any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return io.ErrClosedPipe
	}
	raw, e := json.Marshal(value)
	if e != nil {
		return e
	}
	controller := http.NewResponseController(s.writer)
	_ = controller.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if id >= 0 {
		_, e = fmt.Fprintf(s.writer, "id: %d\n", id)
		if e != nil {
			s.closed = true
			return e
		}
	}
	_, e = fmt.Fprintf(s.writer, "event: %s\ndata: %s\n\n", name, raw)
	if e == nil {
		e = controller.Flush()
	}
	if e != nil {
		s.closed = true
	}
	return e
}
func (s *sseWriter) heartbeat() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return io.ErrClosedPipe
	}
	c := http.NewResponseController(s.writer)
	_ = c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, e := io.WriteString(s.writer, ": ping\n\n")
	if e == nil {
		e = c.Flush()
	}
	if e != nil {
		s.closed = true
	}
	return e
}
func (s *sseWriter) close() { s.mu.Lock(); s.closed = true; s.mu.Unlock() }

// EventBuffer 合并短增量，控制帧前刷新，任何持久化失败会停止后续事件。
type EventBuffer struct {
	mu       sync.Mutex
	pending  strings.Builder
	timer    *time.Timer
	closed   bool
	failure  error
	write    Publish
	interval time.Duration
}

func NewEventBuffer(write Publish) *EventBuffer {
	return &EventBuffer{write: write, interval: 100 * time.Millisecond}
}
func (b *EventBuffer) flush() error {
	if b.timer != nil {
		b.timer.Stop()
		b.timer = nil
	}
	if b.pending.Len() == 0 {
		return nil
	}
	text := b.pending.String()
	if err := b.write("delta", map[string]string{"content": text}); err != nil {
		b.failure = err
		return err
	}
	b.pending.Reset()
	return nil
}
func (b *EventBuffer) Publish(name string, payload any) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.failure != nil {
		return b.failure
	}
	if b.closed {
		return nil
	}
	if name == "delta" {
		raw, _ := json.Marshal(payload)
		var v struct {
			Content string `json:"content"`
		}
		if e := json.Unmarshal(raw, &v); e != nil {
			return e
		}
		b.pending.WriteString(v.Content)
		if javaLength(b.pending.String()) >= 2048 {
			return b.flush()
		}
		if b.timer == nil {
			b.timer = time.AfterFunc(b.interval, func() {
				b.mu.Lock()
				defer b.mu.Unlock()
				if !b.closed && b.failure == nil {
					_ = b.flush()
				}
			})
		}
		return nil
	}
	if e := b.flush(); e != nil {
		return e
	}
	if name == "done" || name == "yield" || name == "error" {
		b.closed = true
	}
	if e := b.write(name, payload); e != nil {
		b.failure = e
		return e
	}
	return nil
}
func (b *EventBuffer) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.timer != nil {
		b.timer.Stop()
		b.timer = nil
	}
	if b.failure != nil {
		b.closed = true
		return b.failure
	}
	if b.closed {
		return nil
	}
	err := b.flush()
	b.closed = true
	return err
}
