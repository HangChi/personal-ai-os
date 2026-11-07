package chat

import (
	"errors"

	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAgentHTTPMalformedPayloadsFailBeforePersistence(t *testing.T) {
	base := "/api/v1/chat/sessions/contract/runs"
	cases := []struct {
		method, path, body string
		status             int
	}{
		{"POST", base, "{", 400}, {"GET", base + "/", "", 404},
		{"POST", base + "/run/events", "{}", 405}, {"GET", base + "/run/events?after=bad", "", 400},
		{"GET", base + "/run/followups", "", 405}, {"POST", base + "/run/followups", "{", 400},
		{"POST", base + "/run/followups", stringMustJSON(map[string]any{"mode": map[string]any{}}), 400},
		{"POST", base + "/run/followups", stringMustJSON(map[string]any{"mode": "QUEUE", "request": map[string]any{"content": map[string]any{}}}), 400},
		{"PATCH", base + "/run/followups/input", "{", 400}, {"PATCH", base + "/run/followups/input", stringMustJSON(map[string]any{"content": map[string]any{}}), 400},
		{"GET", base + "/run/followups/input/steer", "", 405}, {"POST", base + "/run/questions/q/answer", "{", 400},
		{"POST", base + "/run/questions/q/answer", stringMustJSON(map[string]any{"answer": map[string]any{}}), 400},
		{"GET", base + "/run/stop", "", 405}, {"POST", "/api/v1/chat/sessions/contract/stream", "{", 400},
		{"POST", "/api/v1/chat/sessions/contract/stream", "{}", 400},
		{"GET", "/api/v1/chat/uploads/a/content", "", 404},
	}
	for _, tc := range cases {
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
	for _, accept := range []string{"image/png", "application/json"} {
		_, h := moduleHTTPFixture(t)
		r := httptest.NewRequest("GET", base+"/run/events", nil)
		r.Header.Set("Accept", accept)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 406 {
			t.Fatal(w.Code)
		}
	}
	for _, accept := range []string{"", "*/*", "text/*", "TEXT/EVENT-STREAM;q=1"} {
		if !eventAcceptable(accept) {
			t.Fatal(accept)
		}
	}
}
func TestAgentHTTPStorageFailuresAreSanitized(t *testing.T) {
	base := "/api/v1/chat/sessions/contract/runs"
	for _, action := range []string{"get", "events", "start", "followup", "edit", "answer", "stop", "resume", "summarize", "finish"} {
		t.Run(action, func(t *testing.T) {
			path, method, body := base+"/run", "POST", "{}"
			steps := []sqlStep{terminalStep("begin")}
			switch action {
			case "get":
				method = "GET"
			case "events":
				method = "GET"
				path += "/events"
			case "start":
				path = base
				body = stringMustJSON(map[string]any{"content": "目标"})
			case "followup":
				path += "/followups"
				body = stringMustJSON(map[string]any{"mode": "QUEUE", "request": map[string]any{"content": "目标"}})
			case "edit":
				path += "/followups/input"
				method = "PATCH"
				body = stringMustJSON(map[string]any{"content": "目标"})
			case "answer":
				path += "/questions/question/answer"
				body = stringMustJSON(map[string]any{"answer": "目标"})
			case "finish":
				path += "/confirmations/finish"
			default:
				path += "/" + action
			}
			mutate := action == "start" || action == "followup" || action == "edit" || action == "answer" || action == "finish"
			if mutate {
				steps = append(steps, execStep("pg_advisory_xact_lock_shared"), queryStep("SELECT conversation_id", sqlValues("contract", false)))
			}
			fault := queryStep("FROM chat_run WHERE conversation_id")
			fault.err = errors.New("private storage credentials")
			steps = append(steps, fault, terminalStep("rollback"))
			_, h := moduleHTTPFixture(t, steps...)
			r := httptest.NewRequest(method, path, strings.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			if action == "events" {
				r.Header.Set("Accept", "text/event-stream")
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 500 || strings.Contains(w.Body.String(), "credentials") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
func TestAgentEventStreamReadFailureClosesAndUnsubscribes(t *testing.T) {
	step := queryStep("FROM chat_run WHERE")
	step.err = errors.New("event poll failed")
	h, _ := moduleHTTPFixture(t, terminalStep("begin"), step, terminalStep("rollback"))
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	if e := h.events(w, r, "contract", "run", 0); e != nil {
		t.Fatal(e)
	}
	if len(h.module.Runs.signals) != 0 {
		t.Fatal("watcher leak")
	}
	// The writer failure path must also release its watch subscription.
	h, _ = moduleHTTPFixture(t, runReadSteps("COMPLETED", false)...)
	if e := h.events(failingEventWriter{}, httptest.NewRequest("GET", "/", nil), "contract", "run", 0); e != nil {
		t.Fatal(e)
	}
	if len(h.module.Runs.signals) != 0 {
		t.Fatal("watcher leak")
	}
}

type failingEventWriter struct{}

func (failingEventWriter) Header() http.Header       { return http.Header{} }
func (failingEventWriter) WriteHeader(int)           {}
func (failingEventWriter) Write([]byte) (int, error) { return 0, errors.New("socket closed") }
