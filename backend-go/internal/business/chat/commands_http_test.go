package chat

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// Nil dependencies make accidental execution before validation fail the test.
func TestDirectToolHTTPRejectsInvalidInputBeforeExecution(t *testing.T) {
	h := &ModuleHTTP{}
	for _, test := range []struct{ method, accept, body string }{{"GET", "application/json", "{}"}, {"POST", "text/event-stream", "{}"}, {"POST", "application/json", "{}"}, {"POST", "application/json", "null"}, {"POST", "application/json", "{\"tool\":\"createTask\",\"arguments\":[]}"}} {
		r := httptest.NewRequest(test.method, "/api/v1/chat/sessions/c/tools", strings.NewReader(test.body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Accept", test.accept)
		if err := h.serve(httptest.NewRecorder(), r); err == nil {
			t.Fatal(test)
		}
	}
	if !OwnsPath("/api/v1/chat/tools") {
		t.Fatal("catalog route not registered")
	}
}
