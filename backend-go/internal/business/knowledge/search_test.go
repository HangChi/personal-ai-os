package knowledge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOpenSearchMappingRequestsAndVersionFence(t *testing.T) {
	id := "11111111-1111-4111-8111-111111111111"
	created := false
	projected := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "HEAD" {
			if !created {
				w.WriteHeader(404)
			}
			return
		}
		if r.URL.Path == "/test" && r.Method == "PUT" {
			var payload map[string]any
			_ = json.NewDecoder(r.Body).Decode(&payload)
			if !strings.Contains(payloadString(payload), "cjk") {
				t.Error(payload)
			}
			created = true
			return
		}
		if strings.HasSuffix(r.URL.Path, "/_mapping") {
			_, _ = w.Write([]byte(`{"test":{"mappings":{"properties":{"embedding":{"dimension":1024}}}}}`))
			return
		}
		if strings.Contains(r.URL.Path, "/_doc/") {
			if r.URL.Query().Get("version") != "42" || r.URL.Query().Get("version_type") != "external_gte" {
				t.Error(r.URL)
			}
			projected = true
			w.WriteHeader(409)
			return
		}
		if strings.HasSuffix(r.URL.Path, "/_search") {
			_, _ = w.Write([]byte(`{"hits":{"hits":[{"_id":"` + id + `"}]}}`))
			return
		}
		t.Error(r.URL)
	}))
	defer server.Close()
	s, e := NewOpenSearch(server.URL, "test", "", "", 1024, nil)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Project(context.Background(), id, 42, &Chunk{Content: "real", Vector: []float32{1}}); e != nil || !projected {
		t.Fatal(e)
	}
	lex, vec, e := s.Search(context.Background(), "question", []float32{1}, 4)
	if e != nil || len(lex) != 1 || len(vec) != 1 {
		t.Fatal(lex, vec, e)
	}
	for _, url := range []string{"file:///tmp", "https://user:pass@example.test", "http://example.test/?token=secret"} {
		if _, e := NewOpenSearch(url, "test", "", "", 1024, nil); e == nil {
			t.Fatal(url)
		}
	}
}
func payloadString(v any) string { b, _ := json.Marshal(v); return string(b) }
func TestOpenSearchRejectsWrongDimensionsAndOversizedResponse(t *testing.T) {
	for _, response := range []string{`{"test":{"mappings":{"properties":{"embedding":{"dimension":8}}}}}`, strings.Repeat("x", 2_000_001)} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != "HEAD" {
				_, _ = w.Write([]byte(response))
			}
		}))
		s, _ := NewOpenSearch(server.URL, "test", "", "", 1024, nil)
		if e := s.Initialize(context.Background()); e == nil {
			t.Fatal("accepted invalid mapping")
		}
		server.Close()
	}
}
