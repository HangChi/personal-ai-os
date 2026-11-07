package chat

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

// 整条引擎路径使用真实 Service、预算器、协议客户端；仅替换事务存储和 HTTP 传输。
type engineContractTx struct {
	serviceTx
	citations []Citation
	insertErr error
}

func (tx *engineContractTx) MaxSequence(context.Context, string) (int, error) {
	return len(tx.messages), nil
}
func (tx *engineContractTx) InsertMessage(_ context.Context, _ string, m NewMessage, sequence int, at time.Time) error {
	if tx.insertErr != nil {
		return tx.insertErr
	}
	tx.messages = append(tx.messages, Message{ID: int64(sequence), Role: m.Role, Content: m.Content, SequenceNo: sequence, CreatedAt: at, Attachments: m.Attachments})
	return nil
}
func (tx *engineContractTx) MessageIdentity(_ context.Context, _ string, sequence int) (int64, error) {
	return int64(sequence), nil
}
func (tx *engineContractTx) InsertCitations(_ context.Context, _ int64, hits []Citation, _ time.Time) error {
	tx.citations = append([]Citation(nil), hits...)
	return nil
}

type engineContractTransport struct {
	requests []map[string]any
	respond  func(int, map[string]any) (int, string, error)
}

func (tr *engineContractTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	defer r.Body.Close()
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		return nil, err
	}
	tr.requests = append(tr.requests, body)
	status, payload, err := tr.respond(len(tr.requests), body)
	if err != nil {
		return nil, err
	}
	return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": []string{"text/event-stream"}}, Body: io.NopCloser(strings.NewReader(payload)), Request: r}, nil
}
func engineStream(content, tool string) string {
	delta := map[string]any{"content": content}
	if tool != "" {
		delta = map[string]any{"tool_calls": []any{map[string]any{"index": 0, "id": "fixture-call", "type": "function", "function": map[string]string{"name": tool, "arguments": "{}"}}}}
	}
	raw, _ := json.Marshal(map[string]any{"model": "fixture-model", "choices": []any{map[string]any{"delta": delta}}, "usage": map[string]int{"prompt_tokens": 123, "completion_tokens": 7, "total_tokens": 130}})
	newline := string([]byte{10, 10})
	return "data: " + string(raw) + newline + "data: [DONE]" + newline
}
func engineFixture(t *testing.T) (*Engine, *engineContractTx, *engineContractTransport) {
	t.Helper()
	ctx := context.Background()
	tx := &engineContractTx{serviceTx: serviceTx{session: Session{ConversationID: "contract", Title: DefaultTitle}}}
	service, err := NewService(oracleStore{tx}, nil)
	if err != nil {
		t.Fatal(err)
	}
	tr := &engineContractTransport{respond: func(int, map[string]any) (int, string, error) { return 200, engineStream("合成回答", ""), nil }}
	runtime := model.NewRuntime(tr)
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.openai.base-url": "https://fixture.invalid", "spring.ai.openai.api-key": "synthetic-contract-key", "spring.ai.openai.chat.model": "fixture-model"}})
	if err != nil {
		t.Fatal(err)
	}
	resolver, err := model.NewResolver(properties)
	if err != nil {
		t.Fatal(err)
	}
	if err = runtime.Apply(resolver.Resolve(model.Values{})); err != nil {
		t.Fatal(err)
	}
	ai, err := config.BindAIProperties(map[string]string{"app.ai.system-prompt": "合成系统提示"})
	if err != nil {
		t.Fatal(err)
	}
	budget, err := chatcontext.NewBudget(ctx, ai, config.DefaultContextProtectionProperties(), nil, nil, func() string { return "fixture-model" }, runtime.Evidence, nil)
	if err != nil {
		t.Fatal(err)
	}
	registry, err := agent.NewRegistry(&idempotency.Service{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	assembler := &Assembler{Service: service, Budget: budget, Tools: registry.Definitions}
	return &Engine{Service: service, Runtime: runtime, Budget: budget, History: &History{Store: oracleStore{tx}, Budget: budget}, Assembler: assembler, Registry: registry, Attachments: &Attachments{Directory: t.TempDir(), Maximum: 10 << 20}, Agent: config.AgentExecutionProperties{MaxModelCalls: 8, MaxConsecutiveFailures: 3}}, tx, tr
}

type engineEvents struct {
	names    []string
	payloads []any
}

func (v *engineEvents) publish(name string, payload any) error {
	v.names = append(v.names, name)
	v.payloads = append(v.payloads, payload)
	return nil
}
func (v *engineEvents) has(name string) bool {
	for _, n := range v.names {
		if n == name {
			return true
		}
	}
	return false
}
func (v *engineEvents) encoded() string { raw, _ := json.Marshal(v.payloads); return string(raw) }
func prepareEngine(t *testing.T, e *Engine, request StreamRequest) PreparedTurn {
	t.Helper()
	p, err := e.Prepare(context.Background(), "contract", request)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestEngineStreamingPersistsBeforeDoneAndAccountsActualUsage(t *testing.T) {
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	p := prepareEngine(t, e, StreamRequest{Content: "你好", ThinkingLevel: ptr("deep")})
	err := e.Run(context.Background(), p, "turn", "恢复上下文", nil, func(name string, payload any) error {
		if name == "done" && (len(tx.messages) != 2 || tx.messages[1].Content != "合成回答") {
			t.Fatal("done occurred before persistence")
		}
		return events.publish(name, payload)
	})
	if err != nil || strings.Join(events.names, ",") != "start,delta,done" || len(tr.requests) != 1 || e.Metrics.Completed.Load() != 1 || e.Metrics.Failed.Load() != 0 || e.Metrics.Active.Load() != 0 || e.Metrics.Prompt.Load() != 123 || e.Metrics.Completion.Load() != 7 {
		t.Fatal(events.names, err)
	}
	if tx.messages[0].Role != "USER" || tx.messages[1].Role != "ASSISTANT" || tx.session.Title != "你好" {
		t.Fatal(tx.messages, tx.session)
	}
	if tr.requests[0]["enable_thinking"] != true || tr.requests[0]["thinking_budget"] != float64(8192) {
		t.Fatal("thinking parameters missing")
	}
	raw, _ := json.Marshal(tr.requests[0]["messages"])
	if !strings.Contains(string(raw), "恢复上下文") {
		t.Fatal("recovery context lost")
	}
}

func TestEnginePrepareProtectsReadOnlyMissingAndUnconfiguredSessions(t *testing.T) {
	e, tx, _ := engineFixture(t)
	if _, err := e.Prepare(context.Background(), "contract", StreamRequest{}); err == nil || tx.reads != 0 {
		t.Fatal("blank request reached storage")
	}
	if _, err := e.Prepare(context.Background(), "missing", StreamRequest{Content: "你好"}); err == nil {
		t.Fatal("missing session accepted")
	}
	tx.session.ReadOnlyReport = true
	if _, err := e.Prepare(context.Background(), "contract", StreamRequest{Content: "你好"}); err == nil {
		t.Fatal("read-only session accepted")
	}
	tx.session.ReadOnlyReport = false
	e.Runtime = model.NewRuntime(nil)
	if _, err := e.Prepare(context.Background(), "contract", StreamRequest{Content: "你好"}); err == nil {
		t.Fatal("unconfigured model accepted")
	}
}

func TestEngineGuardedEvidenceSkipsGenerationAndPreservesBothCitations(t *testing.T) {
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	checkpoints := 0
	e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
		return RetrievedEvidence{Citations: []Citation{{DocumentID: "a", Content: "甲报价"}, {DocumentID: "b", Content: "乙报价"}}, Emit: true, Retrieval: map[string]string{"status": "CONFLICT"}, Guarded: true, Answer: "资料冲突，需要确认"}, nil
	}
	p := prepareEngine(t, e, StreamRequest{Content: "报销标准", UseKnowledge: engineValue(true)})
	if err := e.Run(context.Background(), p, "turn", "", func(context.Context) error { checkpoints++; return nil }, events.publish); err != nil {
		t.Fatal(err)
	}
	if len(tr.requests) != 0 || len(tx.messages) != 2 || len(tx.citations) != 2 || tx.messages[1].Content != "资料冲突，需要确认" || checkpoints != 1 || strings.Join(events.names, ",") != "start,citations,rag,delta,done" {
		t.Fatal(events.names, tx.messages)
	}
}

func TestEngineWebEvidenceAndSearchFailureStayExplicit(t *testing.T) {
	for _, kind := range []string{"unconfigured", "empty", "failed", "found", "guarded-with-web"} {
		t.Run(kind, func(t *testing.T) {
			e, tx, tr := engineFixture(t)
			events := &engineEvents{}
			if kind != "unconfigured" {
				e.SearchWeb = func(context.Context, string) ([]agent.WebSource, error) {
					switch kind {
					case "empty":
						return nil, nil
					case "failed":
						return nil, errors.New("synthetic search failure")
					default:
						return []agent.WebSource{{Title: "合成网页", URL: "https://evidence.invalid/page", Content: "合成网页证据"}}, nil
					}
				}
			}
			request := StreamRequest{Content: "请联网搜索合成问题"}
			if kind == "guarded-with-web" {
				request.UseKnowledge = engineValue(true)
				e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
					return RetrievedEvidence{Guarded: true, Answer: "需核对", Citations: []Citation{{DocumentID: "blocked"}}}, nil
				}
			}
			p := prepareEngine(t, e, request)
			if err := e.Run(context.Background(), p, "turn", "", nil, events.publish); err != nil {
				t.Fatal(err)
			}
			if len(tr.requests) != 1 || len(tx.messages) != 2 {
				t.Fatal(len(tr.requests), tx.messages)
			}
			raw, _ := json.Marshal(tr.requests[0]["messages"])
			prompt := string(raw)
			switch kind {
			case "unconfigured":
				if !strings.Contains(prompt, "联网搜索尚未配置") {
					t.Fatal(prompt)
				}
			case "empty":
				if !strings.Contains(prompt, "未返回可引用") {
					t.Fatal(prompt)
				}
			case "failed":
				if !strings.Contains(prompt, "联网搜索暂不可用") || strings.Contains(prompt, "synthetic search failure") {
					t.Fatal(prompt)
				}
			default:
				if !events.has("citations") || len(tx.citations) != 1 || tx.citations[0].URL != "https://evidence.invalid/page" {
					t.Fatal(events.names, tx.citations)
				}
			}
			if kind == "guarded-with-web" && (!strings.Contains(prompt, "知识库证据需要核对") || strings.Contains(prompt, "blocked")) {
				t.Fatal(prompt)
			}
		})
	}
}

func TestEngineCalendarClaimIsRepairedWithoutPublishingFalseSuccess(t *testing.T) {
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	tr.respond = func(int, map[string]any) (int, string, error) {
		return 200, engineStream("已成功创建会议", ""), nil
	}
	p := prepareEngine(t, e, StreamRequest{Content: "明天下午两点创建会议"})
	if err := e.Run(context.Background(), p, "turn", "", nil, events.publish); err != nil {
		t.Fatal(err)
	}
	if len(tr.requests) != 2 || tx.messages[1].Content != calendarNotCreated || strings.Contains(events.encoded(), "已成功创建会议") {
		t.Fatal(events.encoded(), tx.messages)
	}
	raw, _ := json.Marshal(tr.requests[1]["messages"])
	if !strings.Contains(string(raw), "本轮没有成功的 createCalendarEvent") {
		t.Fatal("repair instruction missing")
	}
}

func TestEngineFailurePersistsInterruptionAndRedactsUpstreamDetails(t *testing.T) {
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	tr.respond = func(int, map[string]any) (int, string, error) { return 503, "synthetic-private-error", nil }
	p := prepareEngine(t, e, StreamRequest{Content: "你好"})
	if err := e.Run(context.Background(), p, "turn", "", nil, events.publish); err == nil {
		t.Fatal("upstream failure swallowed")
	}
	if !events.has("error") || events.has("done") || len(tx.messages) != 2 || !strings.Contains(tx.messages[1].Content, "连接中断") || strings.Contains(events.encoded(), "synthetic-private-error") || e.Metrics.Failed.Load() != 1 {
		t.Fatal(events.names, tx.messages)
	}
}

func TestEngineSummaryOnlyBlocksModelToolsAndExternalReads(t *testing.T) {
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	external := 0
	e.SearchWeb = func(context.Context, string) ([]agent.WebSource, error) { external++; return nil, nil }
	e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
		external++
		return RetrievedEvidence{}, nil
	}
	tr.respond = func(_ int, request map[string]any) (int, string, error) {
		if tools, ok := request["tools"]; ok && tools != nil {
			t.Error("summary request contained tools")
		}
		return 200, engineStream("", "createTask"), nil
	}
	p := prepareEngine(t, e, StreamRequest{Content: "请联网搜索已有结果", UseKnowledge: engineValue(true)})
	p.summaryOnly = true
	if err := e.Run(context.Background(), p, "turn", "", nil, events.publish); err == nil || !strings.Contains(err.Error(), "已拦截") {
		t.Fatal(err)
	}
	if external != 0 || len(tr.requests) != 1 || len(tx.messages) != 2 || !events.has("error") {
		t.Fatal(external, events.names)
	}
}

func TestEngineAutomaticRecoveryUsesOneToolFreeClosingRequest(t *testing.T) {
	for _, closingWorks := range []bool{true, false} {
		t.Run(map[bool]string{true: "closing-success", false: "receipt-fallback"}[closingWorks], func(t *testing.T) {
			e, tx, tr := engineFixture(t)
			events := &engineEvents{}
			tr.respond = func(n int, request map[string]any) (int, string, error) {
				if n == 1 || !closingWorks {
					return 503, "synthetic upstream error", nil
				}
				if _, ok := request["tools"]; ok {
					t.Error("recovery tool replay enabled")
				}
				return 200, engineStream("已保存结果，部分事项待核实", ""), nil
			}
			p := prepareEngine(t, e, StreamRequest{Content: "整理结果"})
			recovered := &atomic.Bool{}
			p.recovered = recovered
			receiptCalls := 0
			p.receipts = func(context.Context) (string, error) { receiptCalls++; return "write [IN_PROGRESS]: 待核实", nil }
			if err := e.Run(context.Background(), p, "turn", "", nil, events.publish); err != nil {
				t.Fatal(err)
			}
			if len(tr.requests) != 2 || len(tx.messages) != 2 || !recovered.Load() || receiptCalls != 1 || !events.has("warning") || !events.has("done") || events.has("error") {
				t.Fatal(events.names, tx.messages)
			}
			if closingWorks && !strings.Contains(tx.messages[1].Content, "部分事项待核实") {
				t.Fatal(tx.messages)
			}
			if !closingWorks && !strings.Contains(tx.messages[1].Content, "[IN_PROGRESS]") {
				t.Fatal("unknown result lost")
			}
		})
	}
}

func TestEnginePublishAndCheckpointFailuresRespectPersistenceBoundary(t *testing.T) {
	for _, event := range []string{"start", "delta", "done"} {
		t.Run(event, func(t *testing.T) {
			e, tx, _ := engineFixture(t)
			p := prepareEngine(t, e, StreamRequest{Content: "你好"})
			failure := errors.New("synthetic delivery failure")
			err := e.Run(context.Background(), p, "turn", "", nil, func(name string, _ any) error {
				if name == event {
					return failure
				}
				return nil
			})
			if !errors.Is(err, failure) {
				t.Fatal(err)
			}
			if event == "start" && len(tx.messages) != 0 || event != "start" && len(tx.messages) != 2 {
				t.Fatal(tx.messages)
			}
		})
	}
	e, tx, tr := engineFixture(t)
	events := &engineEvents{}
	e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
		return RetrievedEvidence{Guarded: true, Answer: "需确认"}, nil
	}
	p := prepareEngine(t, e, StreamRequest{Content: "问题", UseKnowledge: engineValue(true)})
	if err := e.Run(context.Background(), p, "turn", "", func(context.Context) error { return agent.ErrYield }, events.publish); !errors.Is(err, agent.ErrYield) {
		t.Fatal(err)
	}
	if len(tx.messages) != 0 || len(tr.requests) != 0 || !events.has("yield") || e.Metrics.Yielded.Load() != 1 {
		t.Fatal(events.names, tx.messages)
	}
}

func engineValue[T any](v T) *T { return &v }
