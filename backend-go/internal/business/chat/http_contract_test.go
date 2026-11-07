package chat

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/agent"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
	"testing"
	"time"
)

func moduleHTTPFixture(t *testing.T, steps ...sqlStep) (*ModuleHTTP, http.Handler) {
	r := runsFixture(t, steps...)
	e := r.Engine
	e.Plans = &agent.Plans{Pool: r.Pool}
	e.Usage = &UsageService{Pool: r.Pool, Zone: time.UTC, Now: time.Now}
	m := &Module{Engine: e, Service: e.Service, Runs: r, Budget: e.Budget, Attachments: e.Attachments, schedule: chatSchedule{heartbeat: time.Hour}}
	h := &ModuleHTTP{module: m, storage: &HTTP{service: e.Service, zone: time.UTC}, audit: &agent.Audit{Pool: r.Pool}}
	return h, web.HandleErrors(h.serve, slog.New(slog.NewTextHandler(io.Discard, nil)))
}
func TestChatModuleHTTPReadAndReplayContract(t *testing.T) {
	for _, mode := range []string{"tools", "plan", "audit", "usage", "run", "latest", "events", "headEvents"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			path := "/api/v1/chat/tools"
			method := "GET"
			switch mode {
			case "plan":
				path = "/api/v1/conversations/contract/plan"
				steps = []sqlStep{terminalStep("begin"), queryStep("FROM agent_plan WHERE"), terminalStep("commit")}
			case "audit":
				path = "/api/v1/agent/tool-invocations?page=0&size=5&outcome=SUCCESS"
				steps = []sqlStep{terminalStep("begin"), queryStep("count(*)", sqlValues(0)), queryStep("FROM operation_record"), terminalStep("commit")}
			case "usage":
				path = "/api/v1/usage?days=2"
				steps = []sqlStep{queryStep("FROM token_usage WHERE")}
			case "run", "latest", "events", "headEvents":
				path = "/api/v1/chat/sessions/contract/runs/run"
				steps = runReadSteps("COMPLETED", false)
				if mode == "latest" {
					path = "/api/v1/chat/sessions/contract/runs/latest"
				}
				if mode == "events" || mode == "headEvents" {
					path += "/events"
					if mode == "events" {
						steps = append(steps, runReadSteps("COMPLETED", false)...)
					} else {
						method = "HEAD"
					}
				}
			}
			_, h := moduleHTTPFixture(t, steps...)
			r := httptest.NewRequest(method, path, nil)
			if strings.HasSuffix(path, "/events") {
				r.Header.Set("Accept", "text/event-stream")
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
			if mode == "events" && (!strings.Contains(w.Body.String(), "event: run") || !strings.Contains(w.Body.String(), "COMPLETED")) {
				t.Fatal(w.Body.String())
			}
			if method == "HEAD" && w.Body.Len() != 0 {
				t.Fatal(w.Body.String())
			}
		})
	}
}
func TestChatModuleHTTPMutationReplayContract(t *testing.T) {
	for _, mode := range []string{"start", "stop", "finish", "edit", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			path := "/api/v1/chat/sessions/contract/runs/run"
			body := "{}"
			method := "POST"
			switch mode {
			case "start":
				path = "/api/v1/chat/sessions/contract/runs"
				body = `{"content":"目标"}`
				steps = append(runMutateSteps(), queryStep("FROM chat_run WHERE", runRow("COMPLETED")), terminalStep("commit"))
				steps = append(steps, runReadSteps("COMPLETED", false)...)
			case "stop":
				path += "/stop"
				steps = runReadSteps("STOPPED", false)
			case "finish":
				path += "/confirmations/finish"
				steps = append(runMutateSteps(), queryStep("FROM chat_run WHERE", runRow("COMPLETED")), terminalStep("commit"))
				steps = append(steps, runReadSteps("COMPLETED", false)...)
			case "followup":
				path += "/followups"
				body = `{"mode":"QUEUE","request":{"content":"目标"}}`
				steps = append(runMutateSteps(), queryStep("FROM chat_run WHERE", runRow("COMPLETED")), queryStep("FROM agent_followup", followupRow("QUEUE", "PENDING", "目标")), terminalStep("commit"))
			case "edit", "cancel":
				path += "/followups/input"
				method = "DELETE"
				if mode == "edit" {
					method = "PATCH"
					body = `{"content":"变更"}`
				}
				steps = append(runMutateSteps(), queryStep("FROM chat_run WHERE", runRow("COMPLETED")), queryStep("FROM agent_followup", followupRow("QUEUE", "PENDING", "目标")), execStep("UPDATE agent_followup"), terminalStep("commit"))
				steps = append(steps, runReadSteps("COMPLETED", false)...)
			case "answer":
				path += "/questions/question/answer"
				body = `{"answer":"已答"}`
				steps = append(runMutateSteps(), queryStep("FROM chat_run WHERE", runRow("WAITING_USER")), queryStep("FROM agent_question", questionRow("ANSWERED", "已答")), terminalStep("commit"))
				steps = append(steps, runReadSteps("WAITING_USER", false)...)
			}
			_, h := moduleHTTPFixture(t, steps...)
			r := httptest.NewRequest(method, path, strings.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Idempotency-Key", "run")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
func TestChatModuleHTTPBoundaryContracts(t *testing.T) {
	base := "/api/v1/chat/sessions/contract/runs"
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"POST", "/api/v1/chat/tools", "{}", 405}, {"GET", "/api/v1/usage?days=bad", "", 400}, {"GET", "/api/v1/agent/tool-invocations?outcome=bad", "", 400}, {"GET", "/api/v1/agent/tool-invocations?page=bad", "", 400}, {"POST", "/api/v1/conversations/c/plan", "{}", 405}, {"GET", "/api/v1/conversations/c/d/plan", "", 404}, {"PUT", "/api/v1/chat/context-budget", `{"tokens":0}`, 400}, {"PUT", "/api/v1/chat/context-budget", `{"tokens":{}}`, 400}, {"GET", base, "", 405}, {"GET", base + "/run?after=bad", "", 400}, {"DELETE", base + "/run", "", 405}, {"POST", base + "/run/followups", `{"mode":"bad"}`, 400}, {"POST", base + "/run/followups", `{"mode":"QUEUE","request":null}`, 400}, {"PATCH", base + "/run/followups/input", `{"content":" "}`, 400}, {"GET", base + "/run/followups/input", "", 405}, {"POST", base + "/run/questions/q/answer", `{"answer":" "}`, 400}, {"GET", base + "/run/questions/q/answer", "", 405}, {"GET", base + "/run/followups/input/unknown", "", 404}, {"GET", base + "/run/unknown", "", 404}, {"GET", "/api/v1/chat/uploads", "", 405}, {"GET", "/api/v1/chat/uploads/bad/missing", "", 404}, {"POST", "/api/v1/chat/sessions/contract/tools", `{"tool":"x","arguments":null}`, 400}, {"GET", "/api/v1/chat/sessions/contract/context/x", "", 404},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			_, h := moduleHTTPFixture(t)
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	h, _ := moduleHTTPFixture(t)
	name := "文件.txt"
	a, e := h.module.Attachments.Store(context.Background(), []byte("hello"), &name, nil)
	if e != nil {
		t.Fatal(e)
	}
	for _, method := range []string{"GET", "HEAD"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest(method, "/api/v1/chat/uploads/"+value(a.ID)+"/content?filename=中文%20file.txt", nil)
		if e = h.upload(w, r); e != nil || w.Code != 200 || w.Header().Get("Content-Length") != "5" {
			t.Fatal(e, w.Code, w.Body.String())
		}
		if method == "GET" && w.Body.String() != "hello" {
			t.Fatal(w.Body.String())
		}
	}
	for _, n := range []int64{1, 1024, 1 << 20} {
		if describeLimit(n) == "" {
			t.Fatal(n)
		}
	}
	if turnKey("") == "" || len(turnKey(strings.Repeat("x", 65))) != 64 {
		t.Fatal("turn key")
	}
}
func TestChatDecodeStreamAttachmentContract(t *testing.T) {
	for index, raw := range []string{`{"content":"x","attachments":[null,{"id":"a","size":null}]}`, `{"content":"x","attachments":{}}`, `{"content":"x","attachments":[3]}`, `{"content":"x","attachments":[{"size":{}}]}`} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("POST", "/", strings.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		_, e := streamRequest(w, r)
		if (e == nil) != (index == 0) {
			t.Fatal("attachment contract", index, e)
		}
	}
}

func TestChatContextBudgetAndRouteErrorBoundaries(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"POST", "/api/v1/usage", "{}", 405}, {"POST", "/api/v1/agent/tool-invocations", "{}", 405}, {"GET", "/api/v1/agent/tool-invocations?size=bad", "", 400},
		{"GET", "/api/v1/chat/context-budget", "", 405}, {"PUT", "/api/v1/chat/context-budget", "{bad", 400},
		{"PUT", "/api/v1/chat/context-budget", "{}", 400},
		{"PUT", "/api/v1/chat/context-budget", stringMustJSON(map[string]any{"tokens": 4096, "conversationId": map[string]any{}}), 400},
		{"PUT", "/api/v1/chat/context-budget", stringMustJSON(map[string]any{"tokens": 4096, "thinkingLevel": map[string]any{}}), 400},
		{"POST", "/api/v1/chat/context", "{}", 405}, {"POST", "/api/v1/chat/sessions/contract/context", "{}", 405},
		{"GET", "/api/v1/chat/sessions/contract/stream", "", 405}, {"POST", "/api/v1/chat/sessions/contract/stream/extra", "{}", 404},
		{"POST", "/api/v1/chat/sessions/contract/tools", "{bad", 400},
		{"POST", "/api/v1/chat/sessions/contract/tools", stringMustJSON(map[string]any{"tool": map[string]any{}, "arguments": map[string]any{}}), 400},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			_, h := moduleHTTPFixture(t)
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	for _, path := range []string{"/api/v1/chat/context", "/api/v1/chat/sessions/contract/context", "/api/v1/chat/context-budget"} {
		t.Run(path, func(t *testing.T) {
			steps := []sqlStep{}
			if path != "/api/v1/chat/context" {
				steps = append(steps, queryStep("SELECT prompt_tokens FROM token_usage"))
			}
			_, h := moduleHTTPFixture(t, steps...)
			body := ""
			method := "GET"
			if strings.HasSuffix(path, "budget") {
				method = "PUT"
				body = stringMustJSON(map[string]any{"tokens": 4096, "conversationId": "contract"})
			}
			r := httptest.NewRequest(method, path, strings.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	for _, tc := range []struct {
		path  string
		steps []sqlStep
	}{
		{"/api/v1/usage", []sqlStep{queryStep("FROM token_usage")}},
		{"/api/v1/agent/tool-invocations", []sqlStep{terminalStep("begin"), queryStep("count(*)")}},
		{"/api/v1/conversations/contract/plan", []sqlStep{terminalStep("begin"), queryStep("FROM agent_plan")}},
	} {
		steps := tc.steps
		steps[len(steps)-1].err = errors.New("private SQL credentials")
		if len(steps) > 1 {
			steps = append(steps, terminalStep("rollback"))
		}
		_, h := moduleHTTPFixture(t, steps...)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", tc.path, nil))
		if w.Code != 500 || strings.Contains(w.Body.String(), "credentials") {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}
func stringMustJSON(v any) string {
	b, e := json.Marshal(v)
	if e != nil {
		panic(e)
	}
	return string(b)
}
