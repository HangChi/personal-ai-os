package model

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"

	web "personal-ai-os/backend-go/internal/platform/http"
)

type probeFixture struct {
	Operation, Protocol, Model string
	List, Detail               struct {
		Status int
		Body   string
	}
}
type bindingFixture struct{ Method, Path, ContentType, Body string }
type p3Fixture struct {
	Baseline string `json:"baseline_commit"`
	Inputs   struct {
		Metadata   []string
		Known      []*string
		References []struct{ Name, Base string }
		Bindings   []bindingFixture
		Probes     []probeFixture
	}
	Expected struct {
		Metadata, Known []*int
		References      []json.RawMessage
		Bindings        []struct {
			Status, Code int
			Message      string
		}
		Probes []json.RawMessage
	}
}

func loadP3Fixture(t *testing.T) p3Fixture {
	t.Helper()
	raw, e := os.ReadFile("testdata/java-p3.json")
	if e != nil {
		t.Fatal(e)
	}
	var f p3Fixture
	if e = json.Unmarshal(raw, &f); e != nil {
		t.Fatal(e)
	}
	if f.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(f.Inputs.Bindings) != 54 {
		t.Fatal("wrong baseline")
	}
	return f
}
func TestJavaP3MetadataCatalogAndReferences(t *testing.T) {
	f := loadP3Fixture(t)
	for i, s := range f.Inputs.Metadata {
		if v := ReadWindow([]byte(s)); !reflect.DeepEqual(v, f.Expected.Metadata[i]) {
			t.Errorf("metadata %d=%v want%v JSON=%s", i, valueInt(v), valueInt(f.Expected.Metadata[i]), s)
		}
	}
	for i, name := range f.Inputs.Known {
		if v := LookupKnownWindow(value(name)); !reflect.DeepEqual(v, f.Expected.Known[i]) {
			t.Errorf("known %q=%v want%v", value(name), valueInt(v), valueInt(f.Expected.Known[i]))
		}
	}
	for i, v := range f.Inputs.References {
		ref := findReference(v.Name, v.Base)
		var result any
		if ref != nil {
			result = map[string]any{"tokens": ref.Tokens, "url": ref.URL, "inputLimit": ref.Input}
		}
		compareJSON(t, result, f.Expected.References[i], fmt.Sprint("reference", i))
	}
}
func valueInt(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}
func compareJSON(t *testing.T, actual any, expected []byte, label string) {
	t.Helper()
	a, e := json.Marshal(actual)
	if e != nil {
		t.Fatal(e)
	}
	var av, ev any
	_ = json.Unmarshal(a, &av)
	_ = json.Unmarshal(expected, &ev)
	if !reflect.DeepEqual(av, ev) {
		t.Errorf("%s actual=%s expected=%s", label, a, expected)
	}
}
func TestJavaP3MVCBindingAndValidation(t *testing.T) {
	f := loadP3Fixture(t)
	for i, c := range f.Inputs.Bindings {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			handler := web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
				o, e := web.DecodeJSONObject(w, r, 10<<20)
				if e != nil {
					return e
				}
				if strings.HasSuffix(c.Path, "profiles") {
					v, e := decodeProfile(o)
					if e != nil {
						return e
					}
					if e = validateProfile(v); e != nil {
						return e
					}
				} else {
					v, e := decodeSave(o)
					if e != nil {
						return e
					}
					if e = validateSave(v); e != nil {
						return e
					}
				}
				return respond(w, r, nil)
			}, slog.New(slog.NewTextHandler(io.Discard, nil)))
			req := httptest.NewRequest(c.Method, c.Path, strings.NewReader(c.Body))
			req.Header.Set("Content-Type", c.ContentType)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, req)
			var out struct {
				Code    int
				Message string
			}
			_ = json.Unmarshal(w.Body.Bytes(), &out)
			want := f.Expected.Bindings[i]
			if w.Code != want.Status || out.Code != want.Code || out.Message != want.Message {
				t.Fatalf("status=%d code=%d message=%s want=%+v", w.Code, out.Code, out.Message, want)
			}
		})
	}
}
func TestJavaP3ProbeResults(t *testing.T) {
	f := loadP3Fixture(t)
	for i, c := range f.Inputs.Probes {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.Copy(io.Discard, r.Body)
				spec := c.Detail
				if r.URL.Path == "/v1/models" || r.URL.Path == "/api/tags" {
					spec = c.List
				}
				w.WriteHeader(spec.Status)
				_, _ = io.WriteString(w, spec.Body)
			}))
			defer server.Close()
			p := NewProbe(server.Client().Transport)
			target := ProbeTarget{ParseProtocol(c.Protocol), server.URL, "synthetic-oracle-key"}
			var actual any
			if c.Operation == "models" {
				v := p.Models(context.Background(), target, nil)
				v.LatencyMS = 0
				actual = v
			} else {
				v, e := p.Window(context.Background(), target, c.Model)
				if e != nil {
					t.Fatal(e)
				}
				actual = v
			}
			compareJSON(t, actual, f.Expected.Probes[i], fmt.Sprint("probe", i))
		})
	}
}
