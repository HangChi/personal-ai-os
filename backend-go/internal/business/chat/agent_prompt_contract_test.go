package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"reflect"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/agent"
	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/model"
)

func TestAskUserToolPersistsChoicesAndReplaysAnsweredQuestion(t *testing.T) {
	for _, answered := range []bool{false, true} {
		t.Run(map[bool]string{false: "new-question", true: "answered-replay"}[answered], func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), queryStep("SELECT status FROM chat_run", sqlValues("RUNNING"))}
			if answered {
				steps = append(steps, queryStep("SELECT id FROM agent_question", sqlValues("question")), queryStep("FROM agent_question WHERE run_id", questionRow("ANSWERED", "甲")))
			} else {
				insert := execStep("INSERT INTO agent_question")
				insert.check = func(a []driver.NamedValue) {
					if a[1].Value != "run" || a[2].Value != "版本选择" || a[3].Value != `[{"label":"甲","description":"本地运行"}]` {
						t.Fatal("question input changed", a)
					}
				}
				steps = append(steps, queryStep("SELECT id FROM agent_question"), queryStep("FROM agent_question WHERE run_id"), insert)
			}
			steps = append(steps, terminalStep("commit"))
			s := &Interactions{Pool: contractPool(t, steps...)}
			tool := s.Tool()
			if tool.Model.Name != "askUser" || tool.Write || tool.Limit != 0 {
				t.Fatal("question tool became a write authorization", tool.Model.Name)
			}
			result, err := tool.Action(context.Background(), &agent.Turn{ConversationID: "contract", ID: "run"}, map[string]any{"question": " 版本选择 ", "options": []any{map[string]any{"label": "甲", "description": "本地运行"}}})
			if err != nil || result.Status != "OK" {
				t.Fatal(result, err)
			}
			q, ok := result.Data.(*Question)
			if !ok || q == nil || answered != (q.Status == "ANSWERED") {
				t.Fatal(result)
			}
			if answered && result.Message != "用户已回答，请依据答案继续" || !answered && result.Message != "问题已展示，等待用户回答" {
				t.Fatal(result.Message)
			}
		})
	}
}

func TestAskUserRejectsMalformedChoicesBeforeStorage(t *testing.T) {
	s := &Interactions{Pool: contractPool(t)}
	for _, options := range []any{make(chan int), "wrong", []any{map[string]any{"label": 3}}, []any{map[string]any{"label": ""}}, []any{map[string]any{"label": strings.Repeat("x", 101)}}, []any{map[string]any{"label": "甲", "description": strings.Repeat("x", 301)}}, []any{map[string]any{"label": "甲"}, map[string]any{"label": "甲"}}, []QuestionOption{{Label: "甲"}, {Label: "乙"}, {Label: "丙"}, {Label: "丁"}, {Label: "戊"}}} {
		if _, err := s.Tool().Action(context.Background(), &agent.Turn{}, map[string]any{"question": "请选择", "options": options}); err == nil {
			t.Fatalf("malformed choices accepted: %#v", options)
		}
	}
	for _, question := range []string{"", " ", strings.Repeat("😀", 301)} {
		if _, err := s.Tool().Action(context.Background(), &agent.Turn{}, map[string]any{"question": question}); err == nil {
			t.Fatal("invalid question accepted")
		}
	}
}

func TestConversationRetrievalFiltersRolesTruncatesAndKeepsCurrentQuery(t *testing.T) {
	e, tx, _ := engineFixture(t)
	tx.messages = []Message{{Role: "SYSTEM", Content: "system must not become user evidence"}, {Role: "USER", Content: strings.Repeat("😀", 601)}, {Role: "ASSISTANT", Content: "历史回答"}, {Role: "TOOL", Content: "tool must not become user evidence"}}
	e.Retrieve = func(context.Context, string, int) ([]Citation, error) {
		t.Fatal("legacy retrieval should not win")
		return nil, nil
	}
	e.RetrieveConversationEvidence = func(_ context.Context, query string, history []knowledge.ConversationMessage, topK int) (RetrievedEvidence, error) {
		want := []knowledge.ConversationMessage{{Role: "USER", Content: strings.Repeat("😀", 600)}, {Role: "ASSISTANT", Content: "历史回答"}}
		if query != "当前问题" || topK != 7 || !reflect.DeepEqual(history, want) {
			t.Fatal(query, topK, history)
		}
		return RetrievedEvidence{Citations: []Citation{{DocumentID: "source"}}, Emit: true, Retrieval: "retrieval-metadata"}, nil
	}
	got, err := e.retrieveForPrompt(context.Background(), "当前问题", 7, func(string, any) error { t.Fatal("successful retrieval warned"); return nil }, "contract")
	if err != nil || !got.Emit || len(got.Citations) != 1 || got.Citations[0].DocumentID != "source" {
		t.Fatal(got, err)
	}
}

func TestConversationRetrievalUnavailableHistoryDegradesOnceWithoutStaleEvidence(t *testing.T) {
	for _, mode := range []string{"no-conversation", "no-service", "missing-session", "retrieval-error"} {
		t.Run(mode, func(t *testing.T) {
			e, _, _ := engineFixture(t)
			e.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
			calls := 0
			e.RetrieveConversationEvidence = func(context.Context, string, []knowledge.ConversationMessage, int) (RetrievedEvidence, error) {
				calls++
				return RetrievedEvidence{Guarded: true, Answer: "stale"}, errors.New("private retrieval detail")
			}
			ids := []string{"contract"}
			switch mode {
			case "no-conversation":
				ids = nil
			case "no-service":
				e.Service = nil
			case "missing-session":
				ids[0] = "missing"
			}
			warnings := 0
			got, err := e.retrieveForPrompt(context.Background(), "当前问题", 4, func(name string, data any) error {
				warnings++
				if name != "warning" || data.(map[string]string)["code"] != "KNOWLEDGE_RETRIEVAL_FAILED" {
					t.Fatal(name, data)
				}
				return nil
			}, ids...)
			if err != nil || warnings != 1 || got.Guarded || got.Answer != "" || mode != "retrieval-error" && calls != 0 || mode == "retrieval-error" && calls != 1 {
				t.Fatal(got, calls, warnings, err)
			}
		})
	}
}

func TestAssemblerTokenEstimateIncludesToolsHistoryAndNamedCitations(t *testing.T) {
	e, tx, _ := engineFixture(t)
	tx.messages = []Message{{Role: "USER", Content: "历史问题"}, {Role: "ASSISTANT", Content: "历史回答"}}
	a := e.Assembler
	a.Tools = func() []model.Tool {
		return []model.Tool{{Name: "searchKnowledge", Description: "查找知识", Parameters: map[string]any{"type": "object", "properties": map[string]any{"query": map[string]any{"type": "string"}}}}}
	}
	system, err := a.Assemble(context.Background(), nil, "", "contract", "")
	if err != nil {
		t.Fatal(err)
	}
	want := chatcontext.BudgetTokens(chatcontext.JavaLength(system) + a.ToolChars() + chatcontext.JavaLength("历史问题历史回答"))
	if got, err := a.Estimate(context.Background(), "contract"); err != nil || got != want {
		t.Fatal(got, want, err)
	}
	if a.FixedTokens() != chatcontext.BudgetTokens(chatcontext.JavaLength(a.Budget.AI.SystemPrompt())+a.ToolChars()) || a.ToolChars() == 0 {
		t.Fatal("tool schema missing from fixed budget")
	}
	text, err := a.Assemble(context.Background(), []Citation{{DocumentTitle: ptr("研发规范"), Filename: ptr("design.md"), ChunkIndex: 1, Content: "有依据的片段"}}, "query", "contract", "附件内容")
	if err != nil || !strings.Contains(text, "文档《研发规范》（design.md，第 2 段）") || !strings.Contains(text, "附件内容") {
		t.Fatal(text, err)
	}
	failure := errors.New("synthetic contributor failure")
	a.Contributors = []PromptContributor{{Contribute: func(context.Context, string, string) (string, error) { return "", failure }}}
	if text, err := a.Estimate(context.Background(), "contract"); !errors.Is(err, failure) || text != 0 {
		t.Fatal(text, err)
	}
}

func TestHistoryModelMessagesPreserveRolesAndHandleEmptyOrMissingSession(t *testing.T) {
	e, tx, _ := engineFixture(t)
	tx.messages = []Message{{Role: "SYSTEM", Content: "规则", SequenceNo: 1}, {Role: "USER", Content: "问题", SequenceNo: 2}, {Role: "ASSISTANT", Content: "回答", SequenceNo: 3}}
	got, err := e.History.ModelMessages(context.Background(), "contract")
	want := []model.Message{{Role: "system", Content: "规则"}, {Role: "user", Content: "问题"}, {Role: "assistant", Content: "回答"}}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatal(got, err)
	}
	tx.messages = nil
	window, err := e.History.Window(context.Background(), "contract")
	if err != nil || len(window.Messages) != 0 || window.StartSequence != 1 {
		t.Fatal(window, err)
	}
	window, err = e.History.Window(context.Background(), "missing")
	if err != nil || len(window.Messages) != 0 || window.StartSequence != 1 {
		t.Fatal(window, err)
	}
}
