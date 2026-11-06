package maintenance

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
)

func TestMaintenanceHTTPBoundaries(t *testing.T) {
	s, e := NewService(nil, config.API)
	if e != nil {
		t.Fatal(e)
	}
	h := s.Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, tc := range []struct {
		method, path, accept, body string
		want                       int
	}{
		{"POST", basePath, "", "", 405}, {"GET", basePath + "/verify", "", "", 405}, {"POST", basePath + "/other", "", "", 404}, {"GET", basePath, "text/plain", "", 406}, {"POST", basePath + "/drill-reports", "", strings.Repeat("x", 16385), 400},
	} {
		r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
		r.Header.Set("Accept", tc.accept)
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	s.slots <- struct{}{}
	s.slots <- struct{}{}
	if _, e := s.Verify(context.Background(), []byte("{}")); e == nil {
		t.Fatal("unbounded concurrent validation")
	}
	if !OwnsPath(basePath) || !OwnsPath(basePath+"/x") || OwnsPath(basePath+"-other") {
		t.Fatal("ownership")
	}
}
