package exchange

import (
	"io"
	"log/slog"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"strings"
	"testing"
)

type countedBody struct{ reads int }

func (b *countedBody) Read(p []byte) (int, error) {
	b.reads++
	return 0, io.EOF
}
func (*countedBody) Close() error { return nil }

func TestP7ImportHTTPRejectsInvalidInputBeforeDatabase(t *testing.T) {
	h := (&HTTP{Import: &dataimport.Service{}}).Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	body := &countedBody{}
	r := httptest.NewRequest("POST", "/api/v1/import", body)
	r.ContentLength = dataimport.MaxBytes + 1
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 400 || body.reads != 0 || !strings.Contains(w.Body.String(), "20MB") {
		t.Fatal(w.Code, body.reads, w.Body.String())
	}
	for _, raw := range []string{"", `{"formatVersion":"2"}`, `{"FormatVersion":"1"}`, strings.Repeat(" ", dataimport.MaxBytes+1)} {
		r = httptest.NewRequest("POST", "/api/v1/import", strings.NewReader(raw))
		r.ContentLength = -1
		r.Header.Set("Content-Type", "application/json")
		w = httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}

func TestP7ExportHEADAndNegotiation(t *testing.T) {
	h := (&HTTP{}).Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	r := httptest.NewRequest("HEAD", "/api/v1/export", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 || w.Body.Len() != 0 || !strings.Contains(w.Header().Get("Content-Disposition"), ".json") || w.Header().Get("Content-Type") != "application/json" {
		t.Fatal(w.Code, w.Header(), w.Body.String())
	}
	r = httptest.NewRequest("GET", "/api/v1/export", nil)
	r.Header.Set("Accept", "text/plain")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 406 {
		t.Fatal(w.Code, w.Body.String())
	}
}
