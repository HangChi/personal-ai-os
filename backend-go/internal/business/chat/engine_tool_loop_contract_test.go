package chat

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"reflect"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

func TestEngineToolLoopReturnsReceiptsAndDeduplicatedSourcesToModel(t *testing.T) {
	for _, missingID := range []bool{false, true} {
		t.Run(fmt.Sprintf("missing-call-id-%t", missingID), func(t *testing.T) {
			e, tx, tr := engineFixture(t)
			pool := contractPool(t, queryStep("FROM operation_record"), terminalStep("begin"), execStep("INSERT INTO operation_record"), terminalStep("commit"), terminalStep("begin"), execStep("UPDATE operation_record SET outcome"), terminalStep("commit"))
			store, err := idempotency.NewPostgresStore(pool)
			if err != nil {
				t.Fatal(err)
			}
			idem, err := idempotency.NewService(store)
			if err != nil {
				t.Fatal(err)
			}
			calls := 0
			registry, err := agent.NewRegistry(idem, nil, []agent.Definition{{Model: model.Tool{Name: "mcp_fixture_lookup", Parameters: agent.ObjectSchema(map[string]any{})}, Action: func(_ context.Context, turn *agent.Turn, _ map[string]any) (agent.Result, error) {
				calls++
				if turn.ConversationID != "contract" || turn.ID != "tool-turn" || turn.KnowledgeBlocked {
					t.Fatal("tool identity changed", turn)
				}
				web := []agent.WebSource{{Title: "网页证据", URL: "https://evidence.invalid/source", Content: "可核对的网页片段"}}
				first := turn.AddWebSources(web)
				if again := turn.AddWebSources(web); len(first) != 1 || first[0].Index != 1 || !reflect.DeepEqual(first, again) {
					t.Fatal("web evidence was duplicated or renumbered", first, again)
				}
				hits := []knowledge.Hit{{DocumentID: "document", DocumentTitle: "知识证据", Filename: "contract.md", ChunkIndex: 2, Content: "可核对的知识片段"}}
				citations := turn.AddKnowledgeSources(hits)
				if again := turn.AddKnowledgeSources(hits); len(citations) != 1 || citations[0].Index != 2 || !reflect.DeepEqual(citations, again) {
					t.Fatal("knowledge evidence was duplicated or renumbered", citations, again)
				}
				return agent.OK("检索完成", map[string]any{"web": first, "knowledge": citations}), nil
			}}})
			if err != nil {
				t.Fatal(err)
			}
			e.Registry, e.Assembler.Tools = registry, registry.Definitions
			tr.respond = func(n int, request map[string]any) (int, string, error) {
				if n == 1 {
					payload := engineStream("", "mcp_fixture_lookup")
					if missingID {
						payload = strings.Replace(payload, `"id":"fixture-call"`, `"id":""`, 1)
					}
					return 200, payload, nil
				}
				if n != 2 {
					t.Fatal("unexpected model retry", n)
				}
				messages := request["messages"].([]any)
				assistant := messages[len(messages)-2].(map[string]any)
				call := assistant["tool_calls"].([]any)[0].(map[string]any)
				receipt := messages[len(messages)-1].(map[string]any)
				if assistant["role"] != "assistant" || call["id"] == "" || receipt["role"] != "tool" || receipt["tool_call_id"] != call["id"] {
					t.Fatal("tool receipt lost its model call identity", assistant, receipt)
				}
				var result agent.Result
				if err := json.Unmarshal([]byte(receipt["content"].(string)), &result); err != nil || result.Status != "OK" || result.Message != "检索完成" {
					t.Fatal("durable tool result not returned to model", result, err)
				}
				data := result.Data.(map[string]any)
				if data["web"].([]any)[0].(map[string]any)["index"] != float64(1) || data["knowledge"].([]any)[0].(map[string]any)["index"] != float64(2) {
					t.Fatal("model received inconsistent citation indices", data)
				}
				return 200, engineStream("已根据网页 [1] 和知识库 [2] 核对。", ""), nil
			}
			events := &engineEvents{}
			p := prepareEngine(t, e, StreamRequest{Content: "核对资料"})
			if err = e.Run(context.Background(), p, "tool-turn", "", nil, func(name string, payload any) error {
				if name == "done" && (len(tx.citations) != 2 || len(tx.messages) != 2) {
					t.Fatal("completion preceded citation persistence")
				}
				return events.publish(name, payload)
			}); err != nil {
				t.Fatal(err)
			}
			if calls != 1 || len(tr.requests) != 2 || strings.Join(events.names, ",") != "start,activity,citations,citations,activity,delta,done" {
				t.Fatal(calls, len(tr.requests), events.names)
			}
			if tx.citations[0].URL != "https://evidence.invalid/source" || tx.citations[1].DocumentID != "document" || tx.citations[1].ChunkIndex != 2 || tx.messages[1].Content != "已根据网页 [1] 和知识库 [2] 核对。" {
				t.Fatal("persisted evidence differs from model receipt", tx.citations, tx.messages)
			}
			for _, i := range []int{1, 4} {
				activity := events.payloads[i].(agent.Activity)
				want := "RUNNING"
				if i == 4 {
					want = "OK"
				}
				if activity.Status != want || activity.Tool != "mcp_fixture_lookup" {
					t.Fatal(activity)
				}
			}
		})
	}
}

// Each snapshot is read through the real Plans service and transaction boundary;
// the fixture does not emulate plan updates, locks or PostgreSQL isolation.
func enginePlanSnapshot(title string) []sqlStep {
	steps := []sqlStep{terminalStep("begin")}
	if title == "" {
		return append(steps, queryStep("FROM agent_plan WHERE"), terminalStep("commit"))
	}
	at := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	return append(steps, queryStep("FROM agent_plan WHERE", sqlValues("plan", "contract", "核对资料并生成结论", "ACTIVE", at, at)), queryStep("FROM agent_plan_step", sqlValues(1, title, "PENDING", "必须有可核对依据", nil, nil, nil, nil, nil)), terminalStep("commit"))
}

func TestEngineActivePlanCorrectsPrematureCompletionAndRefreshesPrompt(t *testing.T) {
	for _, finishes := range []bool{false, true} {
		t.Run(fmt.Sprintf("plan-finishes-%t", finishes), func(t *testing.T) {
			e, tx, tr := engineFixture(t)
			var steps []sqlStep
			for _, title := range []string{"核对初始资料", "核对初始资料", "核对初始资料", "核对初始资料", "核对初始资料", "核对补充资料", "核对补充资料"} {
				steps = append(steps, enginePlanSnapshot(title)...)
			}
			last := "核对补充资料"
			if finishes {
				last = ""
			}
			steps = append(steps, enginePlanSnapshot(last)...)
			e.Plans = &agent.Plans{Pool: contractPool(t, steps...), Properties: config.PlanProperties{Enabled: true, MaxInjectChars: 4000}}
			e.Assembler.Contributors = []PromptContributor{{Contribute: func(ctx context.Context, _, id string) (string, error) {
				return e.Plans.Prompt(ctx, id)
			}}}
			tr.respond = func(n int, request map[string]any) (int, string, error) {
				messages := request["messages"].([]any)
				prompt := messages[0].(map[string]any)["content"].(string)
				if n == 1 && !strings.Contains(prompt, "核对初始资料") || n == 2 && (!strings.Contains(prompt, "核对补充资料") || strings.Contains(prompt, "核对初始资料")) || n > 2 {
					t.Fatal("plan prompt not refreshed", n, prompt)
				}
				if n == 2 && !strings.Contains(messages[len(messages)-1].(map[string]any)["content"].(string), "不能宣称任务已完成") {
					t.Fatal("missing corrective instruction", messages)
				}
				return 200, engineStream(fmt.Sprintf("第%d次完成声明", n), ""), nil
			}
			events := &engineEvents{}
			p := prepareEngine(t, e, StreamRequest{Content: "核对并总结"})
			err := e.Run(context.Background(), p, "plan-turn", "", nil, events.publish)
			if finishes {
				if err != nil || !events.has("done") || !events.has("delta") || len(tx.messages) != 2 || tx.messages[1].Content != "第2次完成声明" || strings.Contains(events.encoded(), "第1次完成声明") {
					t.Fatal("premature result leaked or final result lost", err, events.names, tx.messages)
				}
			} else if !errors.Is(err, agent.ErrYield) || events.has("done") || events.has("delta") || !events.has("yield") || len(tx.messages) != 2 || tx.messages[1].Content != "已保存进度，等待用户输入或应用补充要求。" || e.Metrics.Yielded.Load() != 1 {
				t.Fatal("active plan was falsely completed", err, events.names, tx.messages)
			}
			if len(tr.requests) != 2 {
				t.Fatal("plan correction exceeded one retry", len(tr.requests))
			}
		})
	}
}

func TestEnginePlanReadFailuresStopBeforeCompletion(t *testing.T) {
	for _, position := range []int{0, 1, 2, 3} {
		t.Run(fmt.Sprintf("snapshot-%d", position), func(t *testing.T) {
			e, tx, tr := engineFixture(t)
			failure := errors.New("private plan connection failure")
			var steps []sqlStep
			for range position {
				steps = append(steps, enginePlanSnapshot("")...)
			}
			steps = append(steps, terminalStep("begin"), sqlStep{op: "query", match: "FROM agent_plan WHERE", err: failure}, terminalStep("rollback"))
			e.Plans = &agent.Plans{Pool: contractPool(t, steps...), Properties: config.PlanProperties{Enabled: true, MaxInjectChars: 4000}}
			events := &engineEvents{}
			p := prepareEngine(t, e, StreamRequest{Content: "核对计划"})
			if err := e.Run(context.Background(), p, "plan-turn", "", nil, events.publish); !errors.Is(err, failure) || events.has("done") || !events.has("error") || e.Metrics.Failed.Load() != 1 {
				t.Fatal("plan failure converted to successful completion", err, events.names)
			}
			wantCalls := 0
			if position == 3 {
				wantCalls = 1
			}
			if len(tr.requests) != wantCalls || len(tx.messages) != 2 || strings.Contains(events.encoded(), failure.Error()) {
				t.Fatal("plan failure leaked details or retried generation", len(tr.requests), tx.messages, events.encoded())
			}
		})
	}
}

func TestEngineCompletedTurnAccountsUsageAndRunsHooksAfterClientCancellation(t *testing.T) {
	e, tx, _ := engineFixture(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	executor, err := taskexec.New(taskexec.DefaultOptions(), logger)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { executor.Stop() })
	e.Executor = executor
	insert := execStep("INSERT INTO token_usage")
	insert.check = func(args []driver.NamedValue) {
		if args[0].Value != "contract" || args[1].Value != "fixture-model" || args[2].Value != int64(123) || args[3].Value != int64(7) || args[4].Value != "ACTUAL" {
			t.Error("incorrect final usage", args)
		}
	}
	e.Usage = &UsageService{Pool: contractPool(t, terminalStep("begin"), insert, terminalStep("commit")), Now: time.Now, Logger: logger}
	var hookCalls []string
	for _, label := range []string{"first", "second"} {
		e.PostTurn = append(e.PostTurn, func(ctx context.Context, conversation, user, reply string) error {
			if ctx.Err() != nil || conversation != "contract" || user != "合成问题" || reply != "合成回答" {
				t.Error("post-turn hook lost completion data or inherited cancellation", ctx.Err(), conversation, user, reply)
			}
			hookCalls = append(hookCalls, label)
			return errors.New("optional hook failed")
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p := prepareEngine(t, e, StreamRequest{Content: "合成问题"})
	if err = e.Run(ctx, p, "hook-turn", "", nil, func(name string, _ any) error {
		if name == "done" {
			if len(tx.messages) != 2 {
				t.Fatal("completion not persisted before hooks")
			}
			cancel()
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	executor.Shutdown()
	wait, stop := context.WithTimeout(context.Background(), 5*time.Second)
	defer stop()
	if err = executor.Wait(wait); err != nil || !reflect.DeepEqual(hookCalls, []string{"first", "second"}) || executor.Stats().Completed != 2 || e.Metrics.Completed.Load() != 1 {
		t.Fatal("completed answer lost optional work", err, hookCalls, executor.Stats())
	}
}
