// Package agent 提供显式工具注册、单轮硬预算和持久化执行外壳。
package agent

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

var ErrYield = errors.New("执行在安全边界让出")

func Conflict(message string) error { return api.NewBusinessError(api.BusinessConflict, message) }
func Invalid(message string) error  { return api.NewBusinessError(api.ValidationFailed, message) }
func UUID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

type Result struct {
	Status         string  `json:"status"`
	Message        string  `json:"message"`
	Data           any     `json:"data"`
	ConfirmationID *string `json:"confirmationId"`
}

func OK(message string, data any) Result { return Result{Status: "OK", Message: message, Data: data} }

type Activity struct {
	ID       string `json:"id"`
	Tool     string `json:"tool"`
	Status   string `json:"status"`
	Duration int64  `json:"durationMs"`
}
type Turn struct {
	ConversationID, ID string
	Budget             *TurnBudget
	Checkpoint         func(context.Context) error
	Progress           func(Activity)
}

func (t *Turn) Check(ctx context.Context) error {
	if e := ctx.Err(); e != nil {
		return e
	}
	if t.Checkpoint != nil {
		return t.Checkpoint(ctx)
	}
	return nil
}

type TurnBudget struct {
	mu                      sync.Mutex
	config                  config.AgentExecutionProperties
	models, failures, total int
	stopped                 bool
	failed, writes          map[string]bool
	calls                   map[string]int
}

func NewTurnBudget(p config.AgentExecutionProperties) *TurnBudget {
	return &TurnBudget{config: p, failed: map[string]bool{}, writes: map[string]bool{}, calls: map[string]int{}}
}
func limit() error {
	return api.NewBusinessError(api.AgentExecutionLimit, api.AgentExecutionLimit.Message())
}
func (b *TurnBudget) BeforeModel() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped || b.models >= b.config.MaxModelCalls {
		b.stopped = true
		return limit()
	}
	b.models++
	return nil
}
func (b *TurnBudget) BeforeTool(name, digest string, perTool, total int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped || b.failed[name+":"+digest] || (perTool > 0 && b.calls[name] >= perTool) || (total > 0 && b.total >= total) {
		b.stopped = true
		return limit()
	}
	b.calls[name]++
	b.total++
	return nil
}
func (b *TurnBudget) Record(name, digest string, r Result, write bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if r.Status == "ERROR" || r.Status == "REJECTED" {
		b.failed[name+":"+digest] = true
		b.failures++
		if b.failures >= b.config.MaxConsecutiveFailures {
			b.stopped = true
		}
	} else {
		b.failures = 0
	}
	if write && r.Status == "OK" {
		b.writes[name] = true
	}
}
func (b *TurnBudget) HasWrite(name string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.writes[name]
}

type Definition struct {
	Keys      []string
	Model     model.Tool
	Write     bool
	Limit     int
	Normalize func(map[string]any) (map[string]any, error)
	Action    func(context.Context, *Turn, map[string]any) (Result, error)
}
type Recorder interface {
	Success(context.Context, string, string, string) error
	Failure(context.Context, string, string, string) (string, error)
}
type Registry struct {
	definitions map[string]Definition
	idem        *idempotency.Service
	recorder    Recorder
	TotalLimit  int
}

func NewRegistry(idem *idempotency.Service, recorder Recorder, definitions []Definition) (*Registry, error) {
	if idem == nil {
		return nil, errors.New("工具必须装配持久化幂等服务")
	}
	r := &Registry{definitions: map[string]Definition{}, idem: idem, recorder: recorder, TotalLimit: 0}
	for _, d := range definitions {
		if builtin, found := builtinDefinition(d.Model.Name); found {
			d.Model = builtin
		}
		if Strip(d.Model.Name) == "" || d.Action == nil {
			return nil, errors.New("工具定义不完整")
		}
		if _, ok := r.definitions[d.Model.Name]; ok {
			return nil, errors.New("工具名称重复")
		}
		r.definitions[d.Model.Name] = d
	}
	return r, nil
}
func (r *Registry) Definitions() []model.Tool {
	names := make([]string, 0, len(r.definitions))
	for n := range r.definitions {
		names = append(names, n)
	}
	sort.Strings(names)
	out := make([]model.Tool, 0, len(names))
	for _, n := range names {
		d := r.definitions[n].Model
		raw, _ := json.Marshal(d.Parameters)
		_ = json.Unmarshal(raw, &d.Parameters)
		out = append(out, d)
	}
	return out
}
func (r *Registry) Execute(ctx context.Context, t *Turn, call model.ToolCall) (result Result, err error) {
	if t == nil || t.Budget == nil {
		return result, errors.New("工具缺少执行身份")
	}
	if err = t.Check(ctx); err != nil {
		return
	}
	d, exists := r.definitions[call.Function.Name]
	var binding error
	if !exists {
		d.Model.Name = call.Function.Name
		binding = Invalid("工具未注册")
	}
	args := map[string]any{}
	decoder := json.NewDecoder(bytes.NewBufferString(call.Function.Arguments))
	decoder.UseNumber()
	if e := decoder.Decode(&args); e != nil || args == nil {
		binding = Invalid("工具参数必须是JSON对象")
		args = map[string]any{"invalidArguments": call.Function.Arguments}
	} else {
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			binding = Invalid("工具参数包含多余内容")
		}
	}
	if binding == nil {
		binding = validateTypes(args, d.Model.Parameters)
	}
	if binding == nil && d.Normalize != nil {
		normalized, e := d.Normalize(args)
		if e != nil {
			binding = e
		} else {
			args = normalized
		}
	}
	// Java 的 LinkedHashMap 顺序是持久化幂等协议的一部分，不能用 map 排序替代。
	var digest string
	if binding == nil {
		digest, err = ArgumentsDigest(args, d.Keys)
	} else {
		digest, err = idempotency.Digest(args)
	}
	if err != nil {
		return result, err
	}
	if err = t.Budget.BeforeTool(d.Model.Name, digest, d.Limit, r.TotalLimit); err != nil {
		return
	}
	key := UUID()
	if d.Write && d.Model.Name != "startPlanStep" {
		key = idempotency.ToolKey(t.ConversationID, t.ID, d.Model.Name, digest)
	}
	activity := Activity{ID: UUID(), Tool: d.Model.Name, Status: "RUNNING"}
	publish := func() {
		if t.Progress != nil {
			func() { defer func() { _ = recover() }(); t.Progress(activity) }()
		}
	}
	publish()
	started := time.Now()
	saved, executeErr := r.idem.Execute(ctx, idempotency.Request{Key: key, Source: idempotency.SourceTool, Operation: d.Model.Name, Digest: digest, ConversationID: t.ConversationID, TurnID: t.ID}, func(actionCtx context.Context) (idempotency.Result, error) {
		var e error
		if e = t.Check(actionCtx); e != nil {
			return idempotency.Result{}, e
		}
		if binding != nil {
			result = Result{Status: "REJECTED", Message: safeMessage(binding)}
		} else {
			result, e = invoke(d, actionCtx, t, args)
		}
		if e != nil {
			if errors.Is(e, ErrYield) || errors.Is(e, context.Canceled) || errors.Is(e, context.DeadlineExceeded) {
				return idempotency.Result{}, e
			}
			var be *api.BusinessError
			if errors.As(e, &be) {
				result = Result{Status: "REJECTED", Message: be.Error()}
			} else {
				result = Result{Status: "ERROR", Message: "工具执行失败，请检查原因；本轮不要用相同参数重试"}
			}
		}
		outcome := idempotency.Success
		switch result.Status {
		case "REQUIRES_CONFIRMATION":
			outcome = idempotency.RequiresConfirmation
		case "ERROR":
			outcome = idempotency.Failed
		case "REJECTED":
			outcome = idempotency.Rejected
		default:
			if result.Status != "OK" {
				return idempotency.Result{}, errors.New("工具返回非法状态")
			}
		}
		if r.recorder != nil {
			if result.Status == "OK" || result.Status == "REQUIRES_CONFIRMATION" {
				_ = r.recorder.Success(actionCtx, t.ConversationID, d.Model.Name, result.Message)
			} else {
				hint, _ := r.recorder.Failure(actionCtx, t.ConversationID, d.Model.Name, result.Message)
				if hint != "" {
					result.Message += "\n\n" + hint
				}
			}
		}
		t.Budget.Record(d.Model.Name, digest, result, d.Write)
		raw, e := idempotency.CanonicalJSON(result)
		if e != nil {
			return idempotency.Result{}, e
		}
		s := string(raw)
		return idempotency.NewResult(&s, outcome), nil
	})
	activity.Duration = time.Since(started).Milliseconds()
	if executeErr != nil {
		if errors.Is(executeErr, ErrYield) || errors.Is(executeErr, context.Canceled) || errors.Is(executeErr, context.DeadlineExceeded) {
			return Result{}, executeErr
		}
		result = Result{Status: "ERROR", Message: "工具执行失败，请稍后重试"}
		var conflict *idempotency.ConflictError
		if errors.As(executeErr, &conflict) {
			result.Status = "REJECTED"
			result.Message = "该操作的执行结果尚未确定或已用于不同参数，请核对活动记录"
		}
		t.Budget.Record(d.Model.Name, digest, result, false)
	} else {
		if saved.ResponsePayload == nil || json.Unmarshal([]byte(*saved.ResponsePayload), &result) != nil {
			return Result{}, errors.New("工具持久化结果损坏")
		}
		if saved.Replayed {
			t.Budget.Record(d.Model.Name, digest, result, d.Write)
			result = Result{Status: "DUPLICATE", Message: "该操作已执行过，已复用结果。首次结果：" + result.Message, Data: result.Data}
		}
	}
	activity.Status = result.Status
	publish()
	return result, nil
}
func invoke(d Definition, ctx context.Context, t *Turn, args map[string]any) (r Result, e error) {
	defer func() {
		if recover() != nil {
			e = errors.New("工具执行异常")
		}
	}()
	return d.Action(ctx, t, args)
}
func safeMessage(e error) string {
	var b *api.BusinessError
	if errors.As(e, &b) {
		return b.Error()
	}
	return "工具参数无效"
}
func Text(args map[string]any, key string) string { v, _ := args[key].(string); return v }
func Integer(args map[string]any, key string) (int, error) {
	n, ok := args[key].(json.Number)
	if !ok {
		return 0, Invalid(key + " 必须是整数")
	}
	v, e := n.Int64()
	if e != nil || v < -2147483648 || v > 2147483647 {
		return 0, Invalid(key + " 必须是整数")
	}
	return int(v), nil
}
func ObjectSchema(properties map[string]any, required ...string) map[string]any {
	return map[string]any{"type": "object", "properties": properties, "required": required, "additionalProperties": false}
}
func StringSchema() map[string]any { return map[string]any{"type": "string"} }

// Strip 与 Java String.strip 一致，NBSP/窄不换行空格不属于空白。
func Strip(s string) string {
	return strings.TrimFunc(s, func(r rune) bool {
		return r >= 9 && r <= 13 || r >= 0x1c && r <= 0x20 || r == 0x1680 || r >= 0x2000 && r <= 0x2006 || r >= 0x2008 && r <= 0x200a || r == 0x2028 || r == 0x2029 || r == 0x205f || r == 0x3000
	})
}
func ArgumentsDigest(args map[string]any, keys []string) (string, error) {
	if keys == nil {
		return idempotency.Digest(args)
	}
	var b bytes.Buffer
	b.WriteByte('{')
	for i, key := range keys {
		if i > 0 {
			b.WriteByte(',')
		}
		k, _ := idempotency.CanonicalJSON(key)
		v, err := idempotency.CanonicalJSON(args[key])
		if err != nil {
			return "", err
		}
		b.Write(k)
		b.WriteByte(':')
		b.Write(v)
	}
	b.WriteByte('}')
	return idempotency.SHA256(b.String()), nil
}
func validateTypes(args map[string]any, schema map[string]any) error {
	properties, _ := schema["properties"].(map[string]any)
	for key, value := range args {
		if value == nil {
			continue
		}
		spec, _ := properties[key].(map[string]any)
		if spec == nil {
			continue
		} // 与 Spring 的绑定一致，忽略额外字段。
		typ, _ := spec["type"].(string)
		valid := true
		switch typ {
		case "string":
			_, valid = value.(string)
		case "integer":
			_, e := Integer(args, key)
			valid = e == nil
		case "boolean":
			_, valid = value.(bool)
		case "array":
			list, ok := value.([]any)
			valid = ok
			item, _ := spec["items"].(map[string]any)
			if ok && item != nil {
				for _, x := range list {
					if item["type"] == "string" {
						if _, ok := x.(string); !ok {
							valid = false
						}
					}
					if item["type"] == "object" {
						m, ok := x.(map[string]any)
						if !ok || validateTypes(m, item) != nil {
							valid = false
						}
					}
				}
			}
		}
		if !valid {
			return Invalid(key + " 参数类型不正确")
		}
	}
	return nil
}
