package task

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"strings"
	"testing"
	"time"
)

const confirmationBindingTaskID = "00000000-0000-0000-0000-000000000001"

type bindingConfirmationStore struct {
	confirmation.Store
	record        confirmation.Record
	reads, writes int
}

func (s *bindingConfirmationStore) Get(context.Context, string) (confirmation.Record, bool, error) {
	s.reads++
	return s.record, true, nil
}

func (s *bindingConfirmationStore) CompareAndSet(_ context.Context, _ string, expected, next confirmation.Status, _ time.Time) (bool, error) {
	s.writes++
	if s.record.Status != expected {
		return false, nil
	}
	s.record.Status = next
	return true, nil
}

type bindingConfirmationHandler struct{ calls int }

func (*bindingConfirmationHandler) Actions() []confirmation.Action {
	return []confirmation.Action{confirmation.DeleteTask}
}

func (h *bindingConfirmationHandler) Execute(context.Context, confirmation.Action, string) (int, error) {
	h.calls++
	return 1, nil
}

func TestConfirmationHTTPBindingBeforeSideEffects(t *testing.T) {
	// 拒绝边界来自原 ConfirmationController + GlobalExceptionHandler 的 MockMvc 对照。
	// text/plain 保留原处理器的脱敏500；不在此修改 Java 既有状态码契约。
	utf16Body := ""
	for _, b := range []byte(`{"token":"ok"}`) {
		utf16Body += string([]byte{b, 0})
	}
	for _, action := range []string{"confirm", "cancel"} {
		for _, tc := range []struct {
			name, contentType, body, token string
			status, code                   int
		}{
			{"valid", "application/json", `{"token":"ok"}`, "ok", 200, 0},
			{"unknown-field", "application/json", `{"token":"ok","extra":1}`, "ok", 200, 0},
			{"duplicate-last", "application/json", `{"token":"wrong","token":"ok"}`, "ok", 200, 0},
			{"numeric-string", "application/json", `{"token":42}`, "42", 200, 0},
			{"boolean-string", "application/json", `{"token":true}`, "true", 200, 0},
			{"vendor-json", "application/problem+json", `{"token":"ok"}`, "ok", 200, 0},
			{"utf16", "application/json;charset=UTF-16LE", utf16Body, "ok", 200, 0},
			{"wrong-case", "application/json", `{"TOKEN":"ok"}`, "ok", 400, 40000},
			{"trailing-object", "application/json", `{"token":"ok"} {}`, "ok", 400, 40000},
			{"trailing-junk", "application/json", `{"token":"ok"} garbage`, "ok", 400, 40000},
			{"missing", "application/json", `{}`, "ok", 400, 40000},
			{"null-token", "application/json", `{"token":null}`, "ok", 400, 40000},
			{"blank-token", "application/json", `{"token":" \t\u3000"}`, "ok", 400, 40000},
			{"array-token", "application/json", `{"token":["ok"]}`, "ok", 400, 40000},
			{"null-body", "application/json", `null`, "ok", 400, 40000},
			{"array-body", "application/json", `[]`, "ok", 400, 40000},
			{"empty-body", "application/json", ``, "ok", 400, 40000},
			{"plain-media", "text/plain", `{"token":"ok"}`, "ok", 500, 50000},
			{"no-media", "", `{"token":"ok"}`, "ok", 500, 50000},
		} {
			t.Run(action+"/"+tc.name, func(t *testing.T) {
				store := &bindingConfirmationStore{record: confirmation.Record{ID: confirmationBindingTaskID, Token: tc.token,
					Action: confirmation.DeleteTask, Status: confirmation.Pending, ExpiresAt: time.Now().Add(time.Hour)}}
				handler := new(bindingConfirmationHandler)
				registry, err := confirmation.NewRegistryFor([]confirmation.Action{confirmation.DeleteTask}, handler)
				if err != nil {
					t.Fatal(err)
				}
				confirmations, err := confirmation.NewService(store, registry)
				if err != nil {
					t.Fatal(err)
				}
				h, err := NewHTTP(new(Service), new(idempotency.Service), confirmations, slog.New(slog.NewTextHandler(io.Discard, nil)))
				if err != nil {
					t.Fatal(err)
				}
				r := httptest.NewRequest(http.MethodPost, "/api/v1/agent/confirmations/"+confirmationBindingTaskID+"/"+action, strings.NewReader(tc.body))
				if tc.contentType != "" {
					r.Header.Set("Content-Type", tc.contentType)
				}
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				var response struct{ Code int }
				if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
					t.Fatal(err)
				}
				if w.Code != tc.status || response.Code != tc.code {
					t.Fatalf("status=%d body=%s want=%d/%d", w.Code, w.Body.String(), tc.status, tc.code)
				}
				if tc.status != 200 {
					if store.reads != 0 || store.writes != 0 || handler.calls != 0 || store.record.Status != confirmation.Pending {
						t.Fatalf("invalid binding consumed confirmation: reads=%d writes=%d calls=%d status=%s", store.reads, store.writes, handler.calls, store.record.Status)
					}
					return
				}
				if action == "confirm" && (handler.calls != 1 || store.record.Status != confirmation.Confirmed) {
					t.Fatalf("confirmation did not execute once: calls=%d status=%s", handler.calls, store.record.Status)
				}
				if action == "cancel" && (handler.calls != 0 || store.record.Status != confirmation.Cancelled) {
					t.Fatalf("cancel executed a handler or did not cancel: calls=%d status=%s", handler.calls, store.record.Status)
				}
			})
		}
	}
}
