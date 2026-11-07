package knowledge

import (
	"bytes"
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
	"testing"
	"time"
)

func TestKnowledgeHTTPRejectsMethodsBodiesAndMetadataBeforeStorage(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"POST", "/documents/search", "{}", 405}, {"POST", "/search", "{", 400},
		{"PUT", "/governance/spaces", "{}", 405}, {"POST", "/governance/spaces", "{", 400},
		{"POST", "/governance/sources", "{}", 405}, {"POST", "/governance/conflicts", "{}", 405},
		{"PUT", "/search-index", "{}", 405}, {"GET", "/search-index/reindex", "", 405},
		{"GET", "/documents/", "", 404}, {"GET", "/documents/" + contractDocA, "", 405},
		{"POST", "/jobs/" + contractDocA, "{}", 405},
		{"GET", "/governance/sources/" + contractDocA + "/review", "", 405},
		{"PUT", "/governance/sources/" + contractDocA + "/other", "{}", 404},
		{"PUT", "/governance/sources/" + contractDocA, "{", 400},
		{"PUT", "/governance/sources/" + contractDocA, "{" + string(rune(34)) + "spaceId" + string(rune(34)) + ":{}}", 400},
		{"POST", "/governance/conflicts/" + contractDocA + "/other", "{}", 404},
		{"GET", "/governance/conflicts/" + contractDocA + "/resolve", "", 405},
		{"POST", "/governance/conflicts/" + contractDocA + "/resolve", "{", 400},
		{"POST", "/captures", "{", 400}, {"POST", "/captures", "{" + string(rune(34)) + "url" + string(rune(34)) + ":" + string(rune(34)) + "bad" + string(rune(34)) + "}", 400},
		{"GET", "/captures/" + contractDocA, "", 404}, {"GET", "/captures/" + contractDocA + "/refresh", "", 405},
		{"POST", "/captures/" + contractDocA + "/versions", "{}", 405}, {"GET", "/captures/" + contractDocA + "/other", "", 404},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			store := contractStore(t)
			h := &HTTP{Store: store, Governance: &Governance{store}, Projection: &Projection{Store: store}}
			r := httptest.NewRequest(tc.method, basePath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	for _, field := range []string{"decision", "reason"} {
		obj := map[string]any{"decision": "KEEP_BOTH", "reason": "依据"}
		obj[field] = map[string]any{}
		b, _ := json.Marshal(obj)
		store := contractStore(t)
		r := httptest.NewRequest("POST", basePath+"/governance/conflicts/"+contractDocA+"/resolve", bytes.NewReader(b))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		(&HTTP{Store: store, Governance: &Governance{store}}).Handler(slog.Default()).ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	for _, field := range []string{"version", "scope", "authority", "lifecycle", "effectiveFrom", "effectiveUntil"} {
		b, _ := json.Marshal(map[string]any{field: map[string]any{}})
		var obj map[string]json.RawMessage
		json.Unmarshal(b, &obj)
		if _, e := readMetadata(obj); e == nil {
			t.Fatal("object field accepted", field)
		}
	}
}
func TestKnowledgeMultipartFailuresAndJobIdempotency(t *testing.T) {
	for _, mode := range []string{"no-multipart", "missing", "empty", "invalid-key", "valid-key"} {
		t.Run(mode, func(t *testing.T) {
			var body bytes.Buffer
			writer := multipart.NewWriter(&body)
			if mode != "missing" {
				part, e := writer.CreateFormFile("file", "fixture.md")
				if e != nil {
					t.Fatal(e)
				}
				if mode != "empty" {
					io.WriteString(part, "content")
				}
			}
			writer.Close()
			steps := []sqlStep{}
			if mode == "valid-key" {
				bad := terminalStep("begin")
				bad.err = errors.New("private storage detail")
				steps = append(steps, bad)
			}
			store := contractStore(t, steps...)
			handler := (&HTTP{Store: store, Jobs: &Jobs{Store: store}}).Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
			request := httptest.NewRequest("POST", basePath+"/jobs", &body)
			request.Header.Set("Content-Type", writer.FormDataContentType())
			if mode == "no-multipart" {
				request.Header.Set("Content-Type", "application/json")
			}
			if mode == "invalid-key" {
				request.Header.Set("Idempotency-Key", "invalid")
			}
			if mode == "valid-key" {
				request.Header.Set("Idempotency-Key", contractDocA)
			}
			w := httptest.NewRecorder()
			web.MultipartLifecycle(handler, web.MultipartLimits{MaxFileBytes: 1024, MaxRequestBytes: 4096, TempDirectory: t.TempDir()}).ServeHTTP(w, request)
			if w.Code < 400 || strings.Contains(w.Body.String(), "private storage detail") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
func TestKnowledgeReadAndGovernanceHTTPStorageErrorsAreRedacted(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		step               sqlStep
		tx                 bool
	}{
		{"GET", "/captures", "", queryStep("count(*)"), false},
		{"GET", "/captures/" + contractDocA + "/versions", "", queryStep("WHERE c.id"), false},
		{"POST", "/captures/" + contractDocA + "/refresh", "", queryStep("WHERE c.id"), false},
		{"POST", "/search-index/reindex", "{}", execStep("INSERT INTO knowledge_search_outbox"), false},
		{"POST", "/governance/sources/" + contractDocA + "/review", "{}", execStep("UPDATE knowledge_source"), true},
	} {
		t.Run(tc.path, func(t *testing.T) {
			tc.step.err = errors.New("private storage detail")
			steps := []sqlStep{tc.step}
			if tc.tx {
				steps = append([]sqlStep{terminalStep("begin")}, tc.step, terminalStep("rollback"))
			}
			s := contractStore(t, steps...)
			h := &HTTP{Store: s, Governance: &Governance{s}, Projection: &Projection{Store: s}}
			r := httptest.NewRequest(tc.method, basePath+tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			h.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
			if w.Code != 500 || strings.Contains(w.Body.String(), "private storage detail") {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
}
func TestCaptureStorageReadFaultsAndBusinessFailureReceipt(t *testing.T) {
	for _, versions := range []bool{false, true} {
		baseline := []sqlStep{queryStep("count(*)", sqlValues(1)), queryStep("ORDER BY", captureRow(nil))}
		if versions {
			baseline = []sqlStep{queryStep("WHERE c.id", captureRow(nil)), queryStep("count(*)", sqlValues(1)), queryStep("SELECT version", sqlValues(1, "title", "body", "hash", "SUCCEEDED", time.Now().UTC()))}
		}
		for i, target := range baseline {
			for _, fault := range []string{"statement", "rows", "scan"} {
				t.Run(string(rune(65+i))+fault+map[bool]string{true: "versions", false: "list"}[versions], func(t *testing.T) {
					steps := append([]sqlStep{}, baseline[:i+1]...)
					failure := errors.New("capture fault")
					switch fault {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					s := &Captures{Pool: contractStore(t, steps...).Pool}
					var e error
					if versions {
						_, e = s.Versions(context.Background(), contractDocA)
					} else {
						_, e = s.List(context.Background())
					}
					if e == nil {
						t.Fatal("failure swallowed", target.match)
					}
				})
			}
		}
	}
	save := execStep("UPDATE knowledge_capture")
	save.check = func(a []driver.NamedValue) {
		if a[0].Value != "safe business reason" {
			t.Fatal(a)
		}
	}
	s := &Captures{Pool: contractStore(t, queryStep("WHERE c.id", captureRow(nil)), save).Pool, Fetcher: contractFetch(func(context.Context, string) (WebPage, error) {
		return WebPage{}, api.NewBusinessError(api.ValidationFailed, "safe business reason")
	})}
	if _, e := s.Refresh(context.Background(), contractDocA); e == nil {
		t.Fatal("fetch failure swallowed")
	}
}
