//go:build integration

package chat

import (
	"bufio"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/modelgate"
	"personal-ai-os/backend-go/internal/platform/taskexec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPostgresP4CrossInstanceStopHistoryExclusionAndShutdown(t *testing.T) {
	entered := make(chan struct{}, 4)
	var block atomic.Bool
	block.Store(true)
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if block.Load() {
			w.Header().Set("Content-Type", "text/event-stream")
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			entered <- struct{}{}
			<-r.Context().Done()
			return
		}
		emitText(w, "after restart")
	})
	s := f.module.Runs
	ctx := context.Background()
	c := fixtureSession(t, f.module.Service, "stop").ConversationID
	run, e := s.Start(ctx, c, "stop-cross", StreamRequest{Content: "block"})
	if e != nil {
		t.Fatal(e)
	}
	<-entered
	for _, req := range []*http.Request{httptest.NewRequest("DELETE", "/api/v1/chat/sessions/"+c, nil), httptest.NewRequest("POST", "/api/v1/chat/sessions/"+c+"/rewind?fromSequenceNo=1", nil)} {
		w := httptest.NewRecorder()
		f.module.Handler.ServeHTTP(w, req)
		if w.Code != 409 {
			t.Fatal("mutated active history", w.Code, w.Body.String())
		}
	}
	otherCoord, _ := coordination.New(f.pool)
	defer otherCoord.Close(ctx)
	other := NewRuns(ctx, f.pool, f.module.Engine, s.Interactions, otherCoord, s.Gate, allowModel{}, true)
	defer other.Close(ctx)
	if e = other.Stop(ctx, c, run.ID); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, run.ID, "STOPPED")
	if s.Engine.Metrics.Active.Load() != 0 {
		t.Fatal("cross instance stop leaked stream")
	}
	if _, e = s.Start(ctx, c, "close-run", StreamRequest{Content: "block again"}); e != nil {
		t.Fatal(e)
	}
	<-entered
	if e = s.Close(ctx); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, "close-run", "INTERRUPTED")
	block.Store(false)
	if _, e = other.Resume(ctx, c, "close-run", ""); e != nil {
		t.Fatal(e)
	}
	waitRun(t, other, c, "close-run", "COMPLETED")
}

func TestPostgresP4ConfirmationDecisionAndAtomicTurnFailure(t *testing.T) {
	var calls atomic.Int32
	var id string
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1)%2 == 1 {
			emitTool(w, "setTaskPriority", fmt.Sprintf(`{"taskId":%q,"priority":"HIGH"}`, id))
			return
		}
		emitText(w, "需要用户确认")
	})
	ctx := context.Background()
	taskDTO, e := f.tasks.Create(ctx, task.CreateRequest{Title: "confirmation target"})
	if e != nil {
		t.Fatal(e)
	}
	id = taskDTO.ID
	defer f.tasks.Delete(ctx, id)
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "confirm").ConversationID
	run, e := s.Start(ctx, c, "confirm-run", StreamRequest{Content: "priority"})
	if e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, run.ID, "WAITING_CONFIRMATION")
	pending, e := f.confirmations.List(ctx, c, confirmation.Pending)
	if e != nil || len(pending) != 1 {
		t.Fatal(pending, e)
	}
	if _, e = s.FinishConfirmations(ctx, c, run.ID); e == nil {
		t.Fatal("unfinished confirmation accepted")
	}
	before, _ := f.tasks.Get(ctx, id)
	if before.Priority == task.High {
		t.Fatal("tool bypassed confirmation")
	}
	if _, e = f.confirmations.Confirm(ctx, pending[0].ID, pending[0].Token); e != nil {
		t.Fatal(e)
	}
	if _, e = s.FinishConfirmations(ctx, c, run.ID); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, run.ID, "COMPLETED")
	after, _ := f.tasks.Get(ctx, id)
	if after.Priority != task.High {
		t.Fatal(after)
	}
	// 引用失败仍必须回滚助手消息；事后用量另走尽力记账。
	beforeMsgs, _ := f.module.Service.Messages(ctx, c, nil, nil)
	if e = f.module.Service.Complete(ctx, c, "must rollback", []Citation{{DocumentID: strings.Repeat("x", 65), DocumentTitle: ptr("bad"), Filename: ptr("bad.md")}}); e == nil {
		t.Fatal("invalid citation committed")
	}
	afterMsgs, _ := f.module.Service.Messages(ctx, c, nil, nil)
	if len(beforeMsgs) != len(afterMsgs) {
		t.Fatal("partial assistant persisted")
	}
}

func TestPostgresP4AttachmentRebindingCollectionAndEventBatches(t *testing.T) {
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) { emitText(w, "ok") })
	ctx := context.Background()
	a := f.module.Attachments
	c := fixtureSession(t, f.module.Service, "attachment locking").ConversationID
	file, e := a.Store(ctx, []byte("keep me"), ptr("note.txt"), ptr("text/plain"))
	if e != nil {
		t.Fatal(e)
	}
	old := time.Now().Add(-2 * time.Hour)
	if e = os.Chtimes(filepath.Join(a.Directory, *file.ID), old, old); e != nil {
		t.Fatal(e)
	}
	locked, release, done := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		done <- f.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			if e := a.lockBinding(ctx, tx); e != nil {
				return e
			}
			close(locked)
			<-release
			return (&postgresTx{tx: tx, attachments: a}).InsertMessage(ctx, c, NewMessage{Role: "USER", Content: "bind", Attachments: []Attachment{file}}, 1, time.Now())
		})
	}()
	<-locked
	collectDone := make(chan error, 1)
	go func() { _, e := a.Prune(ctx, time.Now()); collectDone <- e }()
	select {
	case e := <-collectDone:
		t.Fatal("collection bypassed uncommitted binding", e)
	case <-time.After(30 * time.Millisecond):
	}
	close(release)
	if e = <-done; e != nil {
		t.Fatal(e)
	}
	if e = <-collectDone; e != nil {
		t.Fatal(e)
	}
	if _, e = a.Read(ctx, *file.ID); e != nil {
		t.Fatal("new committed reference deleted", e)
	}
	insertRun(t, f, c, "batch-run", "COMPLETED")
	execute(t, f.pool, `INSERT INTO chat_run_event(run_id,name,payload) SELECT 'batch-run','delta','{"content":"x"}' FROM generate_series(1,405)`)
	first, e := f.module.Runs.Get(ctx, c, "batch-run", 0)
	if e != nil || len(first.Events) != 200 {
		t.Fatal(first, e)
	}
	second, _ := f.module.Runs.Get(ctx, c, "batch-run", first.Events[199].ID)
	third, _ := f.module.Runs.Get(ctx, c, "batch-run", second.Events[199].ID)
	if len(second.Events) != 200 || len(third.Events) != 5 || third.Events[0].ID <= second.Events[199].ID {
		t.Fatal("cursor batches", len(second.Events), len(third.Events))
	}
	if e = f.module.Service.Delete(ctx, c); e != nil {
		t.Fatal(e)
	}
	if _, e = a.Read(ctx, *file.ID); e == nil {
		t.Fatal("unreferenced file survived session deletion")
	}
}

func TestPostgresP4AllTaskToolsAndAuditConfirmationOverlay(t *testing.T) {
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) { emitText(w, "ok") })
	ctx := context.Background()
	c := fixtureSession(t, f.module.Service, "task tools").ConversationID
	call := func(name, args string) agent.Result {
		t.Helper()
		turn := &agent.Turn{ConversationID: c, ID: agent.UUID(), Budget: agent.NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: 12, MaxConsecutiveFailures: 2})}
		var tc model.ToolCall
		tc.Function.Name = name
		tc.Function.Arguments = args
		v, e := f.module.Engine.Registry.Execute(ctx, turn, tc)
		if e != nil {
			t.Fatal(name, e)
		}
		return v
	}
	created := call("createTask", `{"title":"all tools","priority":"low","tags":["p4"]}`)
	raw, _ := json.Marshal(created.Data)
	var item struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(raw, &item)
	if created.Status != "OK" || item.ID == "" {
		t.Fatal(created)
	}
	defer f.tasks.Delete(ctx, item.ID)
	for _, tc := range []struct{ name, args string }{
		{"getTask", fmt.Sprintf(`{"taskId":%q}`, item.ID)},
		{"findTasks", `{"keyword":"all tools","status":"TODO","tag":"p4","limit":1000}`},
		{"updateTaskDetails", fmt.Sprintf(`{"taskId":%q,"title":"changed","tags":[]}`, item.ID)},
		{"setTaskCompleted", fmt.Sprintf(`{"taskId":%q,"completed":true}`, item.ID)},
		{"setTaskCompleted", fmt.Sprintf(`{"taskId":%q,"completed":false}`, item.ID)},
	} {
		if v := call(tc.name, tc.args); v.Status != "OK" {
			t.Fatal(tc.name, v)
		}
	}
	for _, tc := range []struct{ name, args string }{
		{"scheduleTask", fmt.Sprintf(`{"taskId":%q,"dueAt":"2026-11-06"}`, item.ID)},
		{"setTaskPriority", fmt.Sprintf(`{"taskId":%q,"priority":"HIGH"}`, item.ID)},
		{"deleteTask", fmt.Sprintf(`{"taskIds":[%q]}`, item.ID)},
	} {
		v := call(tc.name, tc.args)
		if v.Status != "REQUIRES_CONFIRMATION" || v.ConfirmationID == nil {
			t.Fatal(tc.name, v)
		}
	}
	pending, e := f.confirmations.List(ctx, c, confirmation.Pending)
	if e != nil || len(pending) != 3 {
		t.Fatal(pending, e)
	}
	for _, p := range pending {
		if e = f.confirmations.Cancel(ctx, p.ID, p.Token); e != nil {
			t.Fatal(e)
		}
	}
	audit, e := (&agent.Audit{Pool: f.pool}).Search(ctx, c, "REQUIRES_CONFIRMATION", 0, 20)
	if e != nil || len(audit.Items) != 3 {
		t.Fatal(audit, e)
	}
	for _, v := range audit.Items {
		if v.Digest == "" || v.ConfirmationStatus == nil || *v.ConfirmationStatus != "CANCELLED" {
			t.Fatal(v)
		}
	}
	for _, tc := range []struct{ name, args, want string }{
		{"setTaskCompleted", `{"taskId":"bad"}`, "必须明确指示是标记完成还是恢复"},
		{"updateTaskDetails", `{"taskId":"bad"}`, "至少需要提供新的标题、描述或标签"},
		{"setTaskPriority", `{"taskId":"bad","priority":"wrong"}`, "优先级只能是 LOW / MEDIUM / HIGH，收到：wrong"},
		{"deleteTask", `{"taskIds":[]}`, "至少需要一个任务标识"},
	} {
		v := call(tc.name, tc.args)
		if v.Status != "REJECTED" || v.Message != tc.want {
			t.Fatal(tc.name, v)
		}
	}
	if _, e = f.tasks.Get(ctx, item.ID); e != nil {
		t.Fatal("cancelled deletion executed", e)
	}
}

func TestPostgresP4StreamFailuresAndEstimatedUsage(t *testing.T) {
	for _, mode := range []string{"empty", "eof", "upstream", "timeout", "estimated", "zero", "prompt-only", "completion-only", "usage-tail", "usage-failure"} {
		t.Run(mode, func(t *testing.T) {
			f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
				switch mode {
				case "upstream":
					http.Error(w, "private failure", 503)
				case "timeout":
					w.Header().Set("Content-Type", "text/event-stream")
					w.WriteHeader(200)
					w.(http.Flusher).Flush()
					<-r.Context().Done()
				case "eof":
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
				case "empty":
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprint(w, "data: [DONE]\n\n")
				case "zero", "prompt-only", "completion-only", "usage-tail", "usage-failure":
					prompt, completion := 0, 0
					if mode == "prompt-only" || mode == "usage-tail" || mode == "usage-failure" {
						prompt = 120
					}
					if mode == "completion-only" || mode == "usage-tail" {
						completion = 8
					}
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprintf(w, "data: {\"model\":\"reported\",\"choices\":[{\"delta\":{\"content\":\"estimate\"}}]}\n\ndata: {\"model\":\"reported\",\"choices\":[],\"usage\":{\"prompt_tokens\":%d,\"completion_tokens\":%d}}\n\n", prompt, completion)
					// 全零尾块不能覆盖前一个有效用量。
					if mode == "usage-tail" {
						fmt.Fprint(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}\n\n")
					}
					fmt.Fprint(w, "data: [DONE]\n\n")
				default:
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"estimate\"}}]}\n\ndata: [DONE]\n\n")
				}
			})
			f.module.Engine.Timeout = 5 * time.Second
			if mode == "timeout" {
				f.module.Engine.Timeout = 250 * time.Millisecond
			}
			c := fixtureSession(t, f.module.Service, mode).ConversationID
			if mode == "usage-failure" {
				execute(t, f.pool, `CREATE FUNCTION p4_reject_usage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic usage failure'; END $$; CREATE TRIGGER p4_reject_usage BEFORE INSERT ON token_usage FOR EACH ROW EXECUTE FUNCTION p4_reject_usage()`)
				defer execute(t, f.pool, `DROP TRIGGER p4_reject_usage ON token_usage; DROP FUNCTION p4_reject_usage()`)
			}
			p, e := f.module.Engine.Prepare(context.Background(), c, StreamRequest{Content: "hi"})
			if e != nil {
				t.Fatal(e)
			}
			var events []string
			e = f.module.Engine.Run(context.Background(), p, agent.UUID(), "", nil, func(n string, v any) error { events = append(events, n); return nil })
			if mode == "estimated" || mode == "empty" || mode == "zero" || mode == "prompt-only" || mode == "completion-only" || mode == "usage-tail" || mode == "usage-failure" {
				if e != nil {
					t.Fatal(e)
				}
				if events[len(events)-1] != "done" {
					t.Fatal(events)
				}
				if mode == "usage-failure" {
					f.module.Engine.Executor.Shutdown()
					if err := f.module.Engine.Executor.Wait(context.Background()); err != nil {
						t.Fatal(err)
					}
					messages, err := f.module.Service.Messages(context.Background(), c, nil, nil)
					if err != nil || len(messages) != 2 || messages[1].Content != "estimate" {
						t.Fatal(messages, err)
					}
					if n := countFor(t, f.pool, `SELECT count(*) FROM token_usage WHERE conversation_id=$1`, c); n != 0 {
						t.Fatal(n)
					}
				} else {
					v := awaitUsage(t, f.pool, c)
					if mode == "estimated" || mode == "empty" || mode == "zero" {
						if v.Source != "ESTIMATED" || v.Model != "" || v.Prompt <= 0 {
							t.Fatal(v)
						}
					} else if v.Source != "ACTUAL" || v.Model != "reported" || (mode == "completion-only" && v.Prompt != 0) || (mode == "prompt-only" && v.Completion != 0) || (mode == "usage-tail" && (v.Prompt != 120 || v.Completion != 8)) {
						t.Fatal(v)
					}
				}
			} else {
				if e == nil || events[len(events)-1] != "error" {
					t.Fatal(mode, e, events)
				}
				messages, _ := f.module.Service.Messages(context.Background(), c, nil, nil)
				if len(messages) != 2 || !strings.Contains(messages[1].Content, "中断") {
					t.Fatal(mode, messages)
				}
			}
		})
	}
}

type allowModel struct{}

func TestPostgresP4RetrievalPortCitationOrderingAndFailureDegradation(t *testing.T) {
	for _, mode := range []string{"off", "hits", "zero", "retrieval-failure", "citation-failure"} {
		t.Run(mode, func(t *testing.T) {
			f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
				var input struct{ Messages []model.Message }
				if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
					t.Error(err)
				}
				if mode == "hits" && !strings.Contains(input.Messages[0].Content, "historical evidence") {
					t.Error("evidence not assembled")
				}
				emitText(w, "reply")
			})
			c := fixtureSession(t, f.module.Service, mode).ConversationID
			calls := 0
			f.module.Engine.Retrieve = func(ctx context.Context, text string, k int) ([]Citation, error) {
				calls++
				if text != "question" || k != 20 {
					t.Error(text, k)
				}
				if mode == "retrieval-failure" {
					return nil, errors.New("synthetic retrieval failure")
				}
				if mode == "zero" {
					return nil, nil
				}
				id := "source"
				if mode == "citation-failure" {
					id = strings.Repeat("x", 65)
				}
				return []Citation{{DocumentID: id, DocumentTitle: ptr("title"), Filename: ptr("source.md"), Content: "historical evidence"}}, nil
			}
			enabled := mode != "off"
			limit := 100
			prepared, err := f.module.Engine.Prepare(context.Background(), c, StreamRequest{Content: "question", UseKnowledge: &enabled, TopK: &limit})
			if err != nil {
				t.Fatal(err)
			}
			var events []string
			err = f.module.Engine.Run(context.Background(), prepared, agent.UUID(), "", nil, func(name string, v any) error {
				events = append(events, name)
				if name == "done" {
					messages, e := f.module.Service.Messages(context.Background(), c, nil, nil)
					if e != nil || len(messages) != 2 || messages[1].Content != "reply" {
						t.Error(messages, e)
					}
					expected := 0
					if mode == "hits" {
						expected = 1
					}
					if len(messages[1].Citations) != expected {
						t.Error("citation snapshot was not committed before done", messages)
					}
				}
				return nil
			})
			if err != nil || events[len(events)-1] != "done" {
				t.Fatal(events, err)
			}
			if enabled && (calls != 1 || events[1] != "citations") || !enabled && calls != 0 {
				t.Fatal(calls, events)
			}
		})
	}
}

func (allowModel) TryAcquire(context.Context) (bool, error) { return true, nil }

type executionFixture struct {
	module        *Module
	pool          *database.Pool
	coordinator   *coordination.Coordinator
	tasks         *task.Service
	confirmations *confirmation.Service
}

func newExecutionFixture(t *testing.T, upstream http.HandlerFunc, overrides ...map[string]string) *executionFixture {
	t.Helper()
	pool := fixturePool(t)
	server := httptest.NewServer(upstream)
	t.Cleanup(server.Close)
	values := map[string]string{"spring.ai.model.chat": "openai", "spring.ai.openai.base-url": server.URL, "spring.ai.openai.api-key": "synthetic-disposable-fixture", "spring.ai.openai.chat.model": "fixture-p4", "app.context.summary.enabled": "false", "app.chat.upload-dir": t.TempDir()}
	for _, override := range overrides {
		for key, value := range override {
			values[key] = value
		}
	}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	models, e := model.OpenModule(context.Background(), pool, p, server.Client().Transport, nil, logger)
	if e != nil {
		t.Fatal(e)
	}
	ts, _ := task.NewPostgresStore(pool)
	tasks, _ := task.NewService(ts)
	is, _ := idempotency.NewPostgresStore(pool)
	idem, _ := idempotency.NewService(is)
	cs, _ := confirmation.NewPostgresStore(pool)
	handler, _ := task.NewConfirmationHandler(tasks)
	registry, _ := confirmation.NewRegistryFor(handler.Actions(), handler)
	confirms, _ := confirmation.NewService(cs, registry)
	coord, _ := coordination.New(pool)
	gate, _ := modelgate.NewLocal(8)
	executor, _ := taskexec.New(taskexec.DefaultOptions(), logger)
	m, e := OpenModule(context.Background(), ModuleOptions{Pool: pool, Properties: p, Models: models, Tasks: tasks, Idempotency: idem, Confirmations: confirms, Coordinator: coord, Gate: gate, Rate: allowModel{}, Executor: executor, Transport: server.Client().Transport, Logger: logger, Enabled: false, Zone: time.UTC})
	if e != nil {
		t.Fatal(e)
	}
	m.Runs.Enabled = true
	t.Cleanup(func() {
		_ = m.Close(context.Background())
		executor.Stop()
		_ = executor.Wait(context.Background())
		_ = coord.Close(context.Background())
	})
	return &executionFixture{m, pool, coord, tasks, confirms}
}
func emitText(w http.ResponseWriter, s string) {
	w.Header().Set("Content-Type", "text/event-stream")
	raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]string{"content": s}}}, "usage": map[string]int{"prompt_tokens": 123, "completion_tokens": 7, "total_tokens": 130}})
	fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
}
func emitTool(w http.ResponseWriter, n, a string) {
	w.Header().Set("Content-Type", "text/event-stream")
	raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{"index": 0, "id": "fixture-call", "type": "function", "function": map[string]string{"name": n, "arguments": a}}}}}}})
	fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
}
func waitRun(t *testing.T, s *Runs, c, id, status string) *Run {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for {
		r, e := s.Get(context.Background(), c, id, 0)
		s.mu.Lock()
		active := s.active[id] != nil
		s.mu.Unlock()
		if e == nil && r.Status == status && !active {
			return r
		}
		if time.Now().After(deadline) {
			t.Fatalf("waiting %s: %+v %v active=%t", status, r, e, active)
		}
		time.Sleep(5 * time.Millisecond)
	}
}
func insertRun(t *testing.T, f *executionFixture, c, id, status string) {
	t.Helper()
	r, _ := idempotency.CanonicalJSON(StreamRequest{Content: "original"})
	execute(t, f.pool, `INSERT INTO chat_run(id,conversation_id,request,status) VALUES($1,$2,$3,$4)`, id, c, string(r), status)
}
func TestPostgresP4PlanSequencingEvidenceAndRevision(t *testing.T) {
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) { emitText(w, "ok") })
	c := fixtureSession(t, f.module.Service, "plan").ConversationID
	p := f.module.Engine.Plans
	ctx := context.Background()
	plan, e := p.Create(ctx, c, "goal", []string{"first", "second"}, false)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = p.Change(ctx, c, 2, "start", ""); e == nil {
		t.Fatal("out of order")
	}
	if _, e = p.Change(ctx, c, 1, "complete", ""); e == nil {
		t.Fatal("no evidence")
	}
	if _, e = p.Change(ctx, c, 1, "start", ""); e != nil {
		t.Fatal(e)
	}
	if e = p.Success(ctx, c, "findTasks", "found one"); e != nil {
		t.Fatal(e)
	}
	plan, e = p.Change(ctx, c, 1, "complete", "")
	if e != nil || plan.Steps[0].ToolResult == nil {
		t.Fatal(plan, e)
	}
	_, _ = p.Change(ctx, c, 2, "start", "")
	hint, e := p.Failure(ctx, c, "createTask", "bad title")
	if e != nil || hint == "" {
		t.Fatal(hint, e)
	}
	if _, e = p.Change(ctx, c, 2, "complete", "not real"); e == nil {
		t.Fatal("failed completed")
	}
	_, _ = p.Change(ctx, c, 2, "start", "")
	plan, e = p.Change(ctx, c, 2, "complete", "verified")
	if e != nil || plan.Status != "COMPLETED" {
		t.Fatal(plan, e)
	}
	old, e := p.Create(ctx, c, "replan", []string{"one"}, false)
	if e != nil {
		t.Fatal(e)
	}
	revised, e := p.Create(ctx, c, "", []string{"replacement"}, true)
	if e != nil || revised.Goal != "replan" || old.ID == revised.ID {
		t.Fatal(revised, e)
	}
	var state string
	_ = f.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT status FROM agent_plan WHERE id=$1`, old.ID).Scan(&state)
	})
	if state != "ABORTED" {
		t.Fatal(state)
	}
}
func TestPostgresP4RunIdempotencyReplayAndUsage(t *testing.T) {
	var count atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) { count.Add(1); emitText(w, "hello") })
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "run").ConversationID
	ctx, cancel := context.WithCancel(context.Background())
	r, e := s.Start(ctx, c, "same-run", StreamRequest{Content: "hi"})
	if e != nil {
		t.Fatal(e)
	}
	cancel()
	r = waitRun(t, s, c, r.ID, "COMPLETED")
	if count.Load() != 1 || len(r.Events) != 3 || r.Events[2].Name != "done" {
		t.Fatal(count.Load(), r)
	}
	if _, e = s.Start(context.Background(), c, r.ID, StreamRequest{Content: "hi"}); e != nil || count.Load() != 1 {
		t.Fatal(e)
	}
	if _, e = s.Start(context.Background(), c, r.ID, StreamRequest{Content: "different"}); e == nil {
		t.Fatal("key conflict ignored")
	}
	msgs, e := f.module.Service.Messages(context.Background(), c, nil, nil)
	if e != nil || len(msgs) != 2 || msgs[1].Content != "hello" {
		t.Fatal(msgs, e)
	}
	_ = awaitUsage(t, f.pool, c)
	n, e := f.module.Engine.Usage.Latest(context.Background(), c)
	if e != nil || n == nil || *n != 123 {
		t.Fatal(n, e)
	}
	if f.module.Engine.Metrics.Active.Load() != 0 {
		t.Fatal("active leak")
	}
	tail, e := s.Get(context.Background(), c, r.ID, r.Events[1].ID)
	if e != nil || len(tail.Events) != 1 {
		t.Fatal(tail, e)
	}
}
func TestPostgresP4ReceiptRecoveryAndUnknownOutcome(t *testing.T) {
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) <= 2 {
			emitTool(w, "createTask", `{"title":"receipt once"}`)
		} else {
			emitText(w, "recovered")
		}
	})
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "recovery").ConversationID
	ctx := context.Background()
	turn := &agent.Turn{ConversationID: c, ID: "crashed", Budget: agent.NewTurnBudget(config.AgentExecutionProperties{MaxModelCalls: 12, MaxConsecutiveFailures: 3})}
	var call model.ToolCall
	call.Function.Name = "createTask"
	call.Function.Arguments = `{"title":"receipt once"}`
	result, e := f.module.Engine.Registry.Execute(ctx, turn, call)
	if e != nil || result.Status != "OK" {
		t.Fatal(result, e)
	}
	raw, _ := json.Marshal(result.Data)
	var dto struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(raw, &dto)
	defer f.tasks.Delete(ctx, dto.ID)
	insertRun(t, f, c, "crashed", "RUNNING")
	if e = s.Recover(ctx); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, "crashed", "COMPLETED")
	var n int
	_ = f.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT count(*) FROM task WHERE title='receipt once'`).Scan(&n)
	})
	if n != 1 {
		t.Fatal("repeated side effect", n)
	}
	for _, outcome := range []string{"IN_PROGRESS", "FAILED"} {
		id := "unknown-" + outcome
		insertRun(t, f, c, id, "RUNNING")
		execute(t, f.pool, `INSERT INTO operation_record(idempotency_key,source,operation,request_digest,conversation_id,turn_id,outcome,created_at) VALUES($1,'TOOL','createTask',$2,$3,$1,$4,now())`, id, strings.Repeat("a", 64), c, outcome)
		if e = s.Recover(ctx); e != nil {
			t.Fatal(e)
		}
		waitRun(t, s, c, id, "INTERRUPTED")
		if _, e = s.Resume(ctx, c, id, ""); e == nil {
			t.Fatal("uncertain action resumed", outcome)
		}
	}
	execute(t, f.pool, `DELETE FROM operation_record WHERE conversation_id=$1`, c)
}
func TestPostgresP4QuestionAnswerAndQueuedFollowups(t *testing.T) {
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			emitTool(w, "askUser", `{"question":"choose?","options":[{"label":"A","description":"alpha"},{"label":"B"}]}`)
		} else {
			emitText(w, "continued")
		}
	})
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "question").ConversationID
	ctx := context.Background()
	r, e := s.Start(ctx, c, "ask", StreamRequest{Content: "help"})
	if e != nil {
		t.Fatal(e)
	}
	r = waitRun(t, s, c, r.ID, "WAITING_USER")
	if r.Question == nil || len(r.Question.Options) != 2 {
		t.Fatal(r)
	}
	if _, e = s.Followup(ctx, c, r.ID, "queue-one", "QUEUE", StreamRequest{Content: "next"}); e != nil {
		t.Fatal(e)
	}
	if _, e = s.ChangeFollowup(ctx, c, r.ID, "queue-one", "edit", "edited"); e != nil {
		t.Fatal(e)
	}
	if e = s.Dispatch(ctx); e != nil || calls.Load() != 1 {
		t.Fatal("queue ran while waiting", e, calls.Load())
	}
	if _, e = s.Answer(ctx, c, r.ID, r.Question.ID, " A "); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, r.ID, "COMPLETED")
	if _, e = s.Answer(ctx, c, r.ID, r.Question.ID, "B"); e == nil {
		t.Fatal("answer overwritten")
	}
	if e = s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	queued := waitRun(t, s, c, "queue-one", "COMPLETED")
	if queued.request.Content != "edited" {
		t.Fatal(queued)
	}
	execute(t, f.pool, `DELETE FROM operation_record WHERE conversation_id=$1`, c)
}
func TestPostgresP4SteerAndStoppedQueue(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			close(entered)
			<-release
			emitTool(w, "createTask", `{"title":"must not execute"}`)
		} else {
			emitText(w, "new intent")
		}
	})
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "steering").ConversationID
	ctx := context.Background()
	r, e := s.Start(ctx, c, "steered", StreamRequest{Content: "original"})
	if e != nil {
		t.Fatal(e)
	}
	<-entered
	if _, e = s.Followup(ctx, c, r.ID, "steer", "STEER", StreamRequest{Content: "only answer"}); e != nil {
		t.Fatal(e)
	}
	close(release)
	waitRun(t, s, c, r.ID, "INTERRUPTED")
	if e = s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, r.ID, "COMPLETED")
	var n int
	_ = f.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT count(*) FROM task WHERE title='must not execute'`).Scan(&n)
	})
	if n != 0 {
		t.Fatal("steer ignored", n)
	}
	transcript, e := s.Interactions.Transcript(ctx, r.ID)
	if e != nil || !strings.Contains(transcript, "only answer") {
		t.Fatal(transcript, e)
	}
	insertRun(t, f, c, "stopped", "STOPPED")
	if _, e = s.Followup(ctx, c, "stopped", "retained", "QUEUE", StreamRequest{Content: "later"}); e != nil {
		t.Fatal(e)
	}
	if e = s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	r, e = s.Get(ctx, c, "stopped", 0)
	if e != nil || len(r.Followups) != 1 || r.Followups[0].Status != "PENDING" {
		t.Fatal(r, e)
	}
}
func TestPostgresP4ConfiguredHeartbeatBothStreams(t *testing.T) {
	for _, mode := range []string{"legacy", "runs"} {
		t.Run(mode, func(t *testing.T) {
			release := make(chan struct{})
			f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
				select {
				case <-release:
				case <-r.Context().Done():
					return
				}
				emitText(w, "after heartbeat")
			}, map[string]string{"app.chat.heartbeat-interval": "5ms"})
			server := httptest.NewServer(f.module.Handler)
			var once sync.Once
			unblock := func() { once.Do(func() { close(release) }) }
			defer func() { unblock(); server.Close() }()
			client := server.Client()
			client.Timeout = 3 * time.Second
			c := fixtureSession(t, f.module.Service, mode).ConversationID
			path := server.URL + "/api/v1/chat/sessions/" + c
			var resp *http.Response
			var err error
			var run *Run
			if mode == "legacy" {
				resp, err = client.Post(path+"/stream", "application/json", strings.NewReader(`{"content":"wait"}`))
			} else {
				run, err = f.module.Runs.Start(context.Background(), c, "ping", StreamRequest{Content: "wait"})
				if err != nil {
					t.Fatal(err)
				}
				resp, err = client.Get(path + "/runs/" + run.ID + "/events")
			}
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			scanner := bufio.NewScanner(resp.Body)
			ping := false
			for scanner.Scan() {
				if scanner.Text() == ": ping" {
					ping = true
					break
				}
			}
			if !ping {
				t.Fatal("configured heartbeat missing", scanner.Err())
			}
			_ = resp.Body.Close()
			unblock()
			if run != nil {
				waitRun(t, f.module.Runs, c, run.ID, "COMPLETED")
			}
		})
	}
}

func TestPostgresP8LegacyCancellationStopsUpstreamWithoutPartialMessage(t *testing.T) {
	upstreamStopped := make(chan struct{})
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"partial answer\"}}]}\n\n")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(upstreamStopped)
	})
	c := fixtureSession(t, f.module.Service, "legacy cancellation").ConversationID
	server := httptest.NewServer(f.module.Handler)
	defer server.Close()
	client := server.Client()
	client.Timeout = 5 * time.Second
	response, err := client.Post(server.URL+"/api/v1/chat/sessions/"+c+"/stream", "application/json", strings.NewReader(`{"content":"begin","useKnowledge":false}`))
	if err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(response.Body)
	found := false
	for scanner.Scan() {
		if scanner.Text() == "event: delta" {
			found = true
			break
		}
	}
	_ = response.Body.Close()
	if !found {
		t.Fatal("no partial delta before cancellation", scanner.Err())
	}
	select {
	case <-upstreamStopped:
	case <-time.After(3 * time.Second):
		t.Fatal("client disconnect did not cancel upstream")
	}
	deadline := time.Now().Add(3 * time.Second)
	for f.module.Engine.Metrics.Active.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	messages, err := f.module.Service.Messages(context.Background(), c, nil, nil)
	if err != nil || len(messages) != 1 || messages[0].Role != "USER" || f.module.Engine.Metrics.Active.Load() != 0 {
		t.Fatal("canceled legacy stream persisted a partial assistant or leaked execution", messages, err)
	}
}

func TestPostgresP4LegacyAndReaderDisconnect(t *testing.T) {
	ready, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			close(ready)
			<-release
		}
		emitText(w, "body")
	})
	c := fixtureSession(t, f.module.Service, "http").ConversationID
	server := httptest.NewServer(f.module.Handler)
	defer server.Close()
	r, e := f.module.Runs.Start(context.Background(), c, "disconnect", StreamRequest{Content: "go"})
	if e != nil {
		t.Fatal(e)
	}
	<-ready
	resp, e := server.Client().Get(server.URL + "/api/v1/chat/sessions/" + c + "/runs/" + r.ID + "/events")
	if e != nil {
		t.Fatal(e)
	}
	scanner := bufio.NewScanner(resp.Body)
	if !scanner.Scan() {
		t.Fatal("no snapshot")
	}
	_ = resp.Body.Close()
	close(release)
	waitRun(t, f.module.Runs, c, r.ID, "COMPLETED")
	resp, e = server.Client().Post(server.URL+"/api/v1/chat/sessions/"+c+"/stream", "application/json", strings.NewReader(`{"content":"legacy","useKnowledge":true}`))
	if e != nil {
		t.Fatal(e)
	}
	data, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	body := string(data)
	if resp.StatusCode != 200 || !strings.Contains(body, "event: citations") || !strings.Contains(body, "event: done") || strings.Index(body, "event: citations") > strings.Index(body, "event: delta") {
		t.Fatal(resp.StatusCode, body)
	}
}
func TestPostgresP4PlanGuardAndCalendar(t *testing.T) {
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) { emitText(w, "已创建会议") })
	c := fixtureSession(t, f.module.Service, "guard").ConversationID
	ctx := context.Background()
	p, e := f.module.Engine.Prepare(ctx, c, StreamRequest{Content: "明天上午安排会议"})
	if e != nil {
		t.Fatal(e)
	}
	var visible strings.Builder
	publish := func(n string, v any) error {
		if n == "delta" {
			visible.WriteString(v.(map[string]string)["content"])
		}
		return nil
	}
	if e = f.module.Engine.Run(ctx, p, "calendar", "", nil, publish); e != nil || visible.String() != calendarNotCreated {
		t.Fatal(visible.String(), e)
	}
	_, e = f.module.Engine.Plans.Create(ctx, c, "unfinished", []string{"one", "two"}, false)
	if e != nil {
		t.Fatal(e)
	}
	p, e = f.module.Engine.Prepare(ctx, c, StreamRequest{Content: "continue"})
	if e != nil {
		t.Fatal(e)
	}
	visible.Reset()
	e = f.module.Engine.Run(ctx, p, "plan", "", nil, publish)
	if !errors.Is(e, agent.ErrYield) || visible.Len() != 0 {
		t.Fatal("unfinished plan leaked output", visible.String(), e)
	}
}
func TestPostgresP4ConcurrentStartAndLeaseFence(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		once.Do(func() { close(entered) })
		<-release
		emitText(w, "done")
	})
	c := fixtureSession(t, f.module.Service, "concurrent").ConversationID
	ctx := context.Background()
	s := f.module.Runs
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Go(func() { _, _ = s.Start(ctx, c, "winner", StreamRequest{Content: "one"}) })
	}
	<-entered
	wg.Wait()
	other, _ := coordination.New(f.pool)
	defer other.Close(ctx)
	lease, e := other.Acquire(ctx, "chat-session:"+c, 1)
	if e != nil || lease != nil {
		t.Fatal("cross instance lease violated", e)
	}
	close(release)
	waitRun(t, s, c, "winner", "COMPLETED")
	lease, e = other.Acquire(ctx, "chat-session:"+c, 1)
	if e != nil || lease == nil {
		t.Fatal(e)
	}
	execute(t, f.pool, `UPDATE runtime_lease SET owner=gen_random_uuid() WHERE resource=$1`, "chat-session:"+c)
	guarded := database.WithWriteGuard(ctx, lease.Fence)
	if e = f.module.Service.Append(guarded, c, []NewMessage{{Role: "USER", Content: "stale"}}); !errors.Is(e, coordination.ErrLeaseLost) {
		t.Fatal("stale owner wrote", e)
	}
	_ = lease.Release(ctx)
}
