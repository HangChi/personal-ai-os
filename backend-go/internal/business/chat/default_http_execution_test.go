package chat

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	web "personal-ai-os/backend-go/internal/platform/http"
)

func httpExecutionCoordinator(t *testing.T, h *ModuleHTTP) {
	t.Helper()
	c, err := coordination.New(h.module.Runs.Pool)
	if err != nil {
		t.Fatal(err)
	}
	h.coordinator = c
	t.Cleanup(func() {
		if err := c.Close(context.Background()); err != nil {
			t.Error(err)
		}
	})
}

func TestLegacyStreamExecutesAndPersistsBeforeReturningSSE(t *testing.T) {
	for _, mode := range []string{"success", "occupied", "acquire-failed", "unconfigured"} {
		t.Run(mode, func(t *testing.T) {
			steps := acquireContractSteps()
			switch mode {
			case "success":
				steps = append(steps, releaseContractSteps()...)
			case "occupied":
				steps[2].rows = nil
			case "acquire-failed":
				steps = []sqlStep{{op: "begin", err: errors.New("private storage detail")}}
			case "unconfigured":
				steps = nil
			}
			h, handler := moduleHTTPFixture(t, steps...)
			httpExecutionCoordinator(t, h)
			h.module.Engine.Plans = nil
			if mode == "unconfigured" {
				h.module.Engine.Runtime = model.NewRuntime(nil)
			}
			r := httptest.NewRequest("POST", "/api/v1/chat/sessions/contract/stream", strings.NewReader(`{"content":"合成问题"}`))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Idempotency-Key", strings.Repeat("x", 65))
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if mode == "success" {
				if w.Code != 200 || w.Header().Get("Content-Type") != "text/event-stream" || w.Header().Get("Cache-Control") != "no-cache" || !strings.Contains(w.Body.String(), "event: start") || !strings.Contains(w.Body.String(), "event: done") || !strings.Contains(w.Body.String(), "合成回答") || h.module.Engine.Metrics.Completed.Load() != 1 {
					t.Fatal(w.Code, w.Body.String())
				}
				messages, err := h.module.Service.Messages(context.Background(), "contract", nil, nil)
				if err != nil || len(messages) != 2 || messages[1].Content != "合成回答" {
					t.Fatal("SSE ended before saving answer", messages, err)
				}
			} else if w.Code < 400 || strings.Contains(w.Body.String(), "private storage detail") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}

func TestDirectToolHTTPUsesFencedIdempotencyAndReleasesSessionLease(t *testing.T) {
	steps := acquireContractSteps()
	steps = append(steps, queryStep("FROM operation_record"), terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), execStep("INSERT INTO operation_record"), terminalStep("commit"))
	// A command receipt surrounds the individual tool's own durable receipt.
	steps = append(steps, queryStep("FROM operation_record"), terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), execStep("INSERT INTO operation_record"), terminalStep("commit"))
	for range 2 {
		steps = append(steps, terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), execStep("UPDATE operation_record SET outcome"), terminalStep("commit"))
	}
	steps = append(steps, releaseContractSteps()...)
	h, handler := moduleHTTPFixture(t, steps...)
	httpExecutionCoordinator(t, h)
	store, err := idempotency.NewPostgresStore(h.module.Runs.Pool)
	if err != nil {
		t.Fatal(err)
	}
	idem, err := idempotency.NewService(store)
	if err != nil {
		t.Fatal(err)
	}
	calls := 0
	registry, err := agent.NewRegistry(idem, nil, []agent.Definition{{Keys: []string{"query"}, Model: model.Tool{Name: "findTasks", Parameters: agent.ObjectSchema(map[string]any{"query": agent.StringSchema()}, "query")}, Action: func(_ context.Context, turn *agent.Turn, args map[string]any) (agent.Result, error) {
		calls++
		if turn.ConversationID != "contract" || args["query"] != "合成查询" {
			t.Fatal(turn, args)
		}
		return agent.OK("已查询", map[string]any{"count": 1}), nil
	}}})
	if err != nil {
		t.Fatal(err)
	}
	h.module.Engine.Registry = registry
	r := httptest.NewRequest("POST", "/api/v1/chat/sessions/contract/tools", strings.NewReader(`{"tool":"findTasks","arguments":{"query":"合成查询"}}`))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Idempotency-Key", "10000000-0000-4000-8000-000000000001")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 200 || calls != 1 || !strings.Contains(w.Body.String(), "已查询") || !strings.Contains(w.Body.String(), `"replayed":false`) {
		t.Fatal(w.Code, w.Body.String(), calls)
	}
}

func TestDirectToolHTTPRejectsUnsafeRequestsAndReleasesOnToolError(t *testing.T) {
	for _, mode := range []string{"read-only", "occupied", "acquire-failed", "unknown-tool", "bad-key", "bad-tool-type", "bad-arguments", "not-acceptable"} {
		t.Run(mode, func(t *testing.T) {
			var steps []sqlStep
			if mode == "occupied" {
				steps = acquireContractSteps()
				steps[2].rows = nil
			}
			if mode == "acquire-failed" {
				steps = []sqlStep{{op: "begin", err: errors.New("private coordinator detail")}}
			}
			if mode == "unknown-tool" || mode == "bad-key" {
				steps = append(acquireContractSteps(), releaseContractSteps()...)
			}
			h, handler := moduleHTTPFixture(t, steps...)
			httpExecutionCoordinator(t, h)
			if mode == "read-only" {
				h.module.Engine.Service.store.(oracleStore).tx.(*engineContractTx).session.ReadOnlyReport = true
			}
			body := `{"tool":"unknown","arguments":{}}`
			if mode == "bad-tool-type" {
				body = `{"tool":{},"arguments":{}}`
			}
			if mode == "bad-arguments" {
				body = `{"tool":"unknown","arguments":[]}`
			}
			r := httptest.NewRequest("POST", "/api/v1/chat/sessions/contract/tools", strings.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Idempotency-Key", "10000000-0000-4000-8000-000000000001")
			if mode == "bad-key" {
				r.Header.Set("Idempotency-Key", "invalid")
			}
			if mode == "not-acceptable" {
				r.Header.Set("Accept", "image/png")
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code < 400 || strings.Contains(w.Body.String(), "private coordinator detail") {
				t.Fatal(w.Code, w.Body.String())
			}
			if mode == "not-acceptable" && w.Code != 406 {
				t.Fatal(w.Code)
			}
		})
	}
}

func TestChatMultipartUploadRoundtripAndInputGuards(t *testing.T) {
	for _, mode := range []string{"success", "empty-file", "too-large", "missing-file"} {
		t.Run(mode, func(t *testing.T) {
			h, handler := moduleHTTPFixture(t)
			handler = web.MultipartLifecycle(handler, web.MultipartLimits{MaxFileBytes: 1024, MaxRequestBytes: 2048, TempDirectory: t.TempDir()})
			h.module.Attachments.Maximum = 32
			var body bytes.Buffer
			form := multipart.NewWriter(&body)
			payload := "合成附件内容"
			if mode == "too-large" {
				payload = strings.Repeat("x", 33)
			}
			if mode == "empty-file" {
				payload = ""
			}
			if mode != "missing-file" {
				file, err := form.CreateFormFile("file", "fixture.txt")
				if err != nil {
					t.Fatal(err)
				}
				if _, err = io.WriteString(file, payload); err != nil {
					t.Fatal(err)
				}
			}
			if err := form.Close(); err != nil {
				t.Fatal(err)
			}
			r := httptest.NewRequest("POST", "/api/v1/chat/uploads", &body)
			r.Header.Set("Content-Type", form.FormDataContentType())
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if mode != "success" {
				if w.Code != 400 {
					t.Fatal(w.Code, w.Body.String())
				}
				return
			}
			var result struct {
				Data Attachment `json:"data"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil || w.Code != 200 || value(result.Data.ID) == "" || result.Data.Size != int64(len(payload)) {
				t.Fatal(result, w.Code, w.Body.String(), err)
			}
			content := httptest.NewRecorder()
			handler.ServeHTTP(content, httptest.NewRequest("GET", "/api/v1/chat/uploads/"+value(result.Data.ID)+"/content", nil))
			if content.Code != 200 || content.Body.String() != payload || !strings.Contains(content.Header().Get("Content-Disposition"), value(result.Data.ID)) {
				t.Fatal(content.Code, content.Body.String())
			}
		})
	}
}

func TestLegacyStreamDisconnectStopsModelAndDoesNotAppendInterruptedAnswer(t *testing.T) {
	steps := append(acquireContractSteps(), releaseContractSteps()...)
	h, _ := moduleHTTPFixture(t, steps...)
	httpExecutionCoordinator(t, h)
	h.module.Engine.Plans = nil
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h.module.Engine.Runtime = captureDisconnectRuntime(t, cancel)
	r := httptest.NewRequest("POST", "/api/v1/chat/sessions/contract/stream", strings.NewReader(`{"content":"合成问题"}`)).WithContext(ctx)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	web.HandleErrors(h.serve, slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
	messages, err := h.module.Service.Messages(context.Background(), "contract", nil, nil)
	if err != nil || len(messages) != 1 || messages[0].Role != "USER" || strings.Contains(w.Body.String(), "event: done") {
		t.Fatal(messages, err, w.Body.String())
	}
}

func captureDisconnectRuntime(t *testing.T, cancel context.CancelFunc) *model.Runtime {
	t.Helper()
	// Keep the actual model protocol and let cancellation happen at the transport
	// boundary, after the legacy stream has persisted its user message.
	runtime := model.NewRuntime(disconnectTransport{cancel: cancel})
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.openai.base-url": "https://fixture.invalid", "spring.ai.openai.api-key": "synthetic", "spring.ai.openai.chat.model": "fixture-model"}})
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
	return runtime
}

type disconnectTransport struct{ cancel context.CancelFunc }

func (tr disconnectTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	tr.cancel()
	return nil, context.Canceled
}
