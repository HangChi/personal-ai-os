package workflow

import (
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWorkflowHTTPRejectsInvalidInputsBeforeDatabase(t *testing.T) {
	h := (&Service{}).Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, test := range []struct {
		method, path, body, accept string
		status                     int
	}{
		{"POST", basePath, `{"name":2}`, "", 400},
		{"POST", basePath, `[]`, "", 400},
		{"PATCH", basePath + "/invalid", `{}`, "", 400},
		{"POST", basePath + "/runs/bad/retry", `{}`, "", 400},
		{"DELETE", basePath, ``, "", 405},
		{"GET", basePath, ``, "text/plain", 406},
		{"GET", basePath + "/00000000-0000-0000-0000-000000000000/run", ``, "", 405},
		{"POST", basePath + "/00000000-0000-0000-0000-000000000000/runs", ``, "", 405},
		{"POST", basePath + "/00000000-0000-0000-0000-000000000000", `{}`, "", 405},
		{"GET", basePath + "/00000000-0000-0000-0000-000000000000/other", ``, "", 404},
	} {
		t.Run(test.path+test.method+test.accept, func(t *testing.T) {
			r := httptest.NewRequest(test.method, test.path, strings.NewReader(test.body))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Accept", test.accept)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != test.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	if !OwnsPath(basePath) || !OwnsPath(basePath+"/x") || OwnsPath(basePath+"-other") {
		t.Fatal("route ownership")
	}
}
