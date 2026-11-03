package model

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbeProtocolHeadersPreferredOrderAndBoundedDetails(t *testing.T) {
	for _, protocol := range []Protocol{OpenAI, Anthropic, Ollama} {
		t.Run(string(protocol), func(t *testing.T) {
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.Copy(io.Discard, r.Body)
				if protocol == Anthropic {
					if r.Header.Get("x-api-key") != "synthetic" || r.Header.Get("anthropic-version") != "2023-06-01" || r.Header.Get("Authorization") != "" {
						t.Error("anthropic headers")
					}
				} else if r.Header.Get("Authorization") != "Bearer synthetic" {
					t.Error("bearer headers")
				}
				if protocol == Ollama {
					if r.URL.Path != "/api/tags" {
						t.Error(r.URL.Path)
					}
					fmt.Fprint(w, `{"models":[{"name":"custom","context_length":8192}]}`)
				} else {
					if r.URL.Path != "/v1/models" {
						t.Error(r.URL.Path)
					}
					fmt.Fprint(w, `{"data":[{"id":"custom","context_length":8192}]}`)
				}
			}))
			defer s.Close()
			p := NewProbe(s.Client().Transport)
			v := p.Models(context.Background(), ProbeTarget{protocol, s.URL + "/v1/", "synthetic"}, nil)
			if !v.OK || v.ContextWindows["custom"] != 8192 {
				t.Fatal(v)
			}
		})
	}
	if v := orderedTargets([]string{"a", "b", "c"}, []string{"missing", "c", "c"}); strings.Join(v, ",") != "c,a,b" {
		t.Fatal(v)
	}
	var active, maximum, calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		if r.URL.Path == "/v1/models" {
			fmt.Fprint(w, `{"data":[`)
			for i := 0; i < 30; i++ {
				if i > 0 {
					fmt.Fprint(w, ",")
				}
				fmt.Fprintf(w, `{"id":"%02d"}`, i)
			}
			fmt.Fprint(w, "]}")
			return
		}
		calls.Add(1)
		n := active.Add(1)
		defer active.Add(-1)
		for old := maximum.Load(); n > old && !maximum.CompareAndSwap(old, n); old = maximum.Load() {
		}
		if strings.HasSuffix(r.URL.Path, "29") {
			w.WriteHeader(429)
			return
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(50 * time.Millisecond):
			fmt.Fprint(w, `{"context_length":4096}`)
		}
	}))
	defer server.Close()
	p := NewProbe(server.Client().Transport)
	out := p.Models(context.Background(), ProbeTarget{OpenAI, server.URL, "synthetic"}, []string{"29"})
	if !out.OK || maximum.Load() > 4 || calls.Load() > 4 {
		t.Fatal("unbounded detail/429 scheduling", out, maximum.Load(), calls.Load())
	}
	p.detailBudget = 30 * time.Millisecond
	p.detailTimeout = 20 * time.Millisecond
	calls.Store(0)
	start := time.Now()
	out = p.Models(context.Background(), ProbeTarget{OpenAI, server.URL, "synthetic"}, nil)
	if !out.OK || time.Since(start) > 250*time.Millisecond || calls.Load() > 12 {
		t.Fatal("detail budget not enforced")
	}
}
func TestAudioResultStatesAndCancellation(t *testing.T) {
	f := &readFixture{}
	read, _ := NewReadService(f, resolver(t, nil, nil))
	a := NewAudioService(read, nil)
	ctx := context.Background()
	status, e := a.Status(ctx)
	if e != nil || status.Available || status.Reason == nil {
		t.Fatal(status, e)
	}
	out, e := a.Transcribe(ctx, []byte("x"), "x.webm", "")
	if e != nil || !out.Unavailable {
		t.Fatal(out, e)
	}
	f.current.AudioModel = ptr("whisper-1")
	status, _ = a.Status(ctx)
	if status.Available || !strings.Contains(value(status.Reason), "API Key") {
		t.Fatal(status)
	}
	f.current.APIKey = ptr("synthetic-audio-key")
	for _, body := range []string{`{"text":""}`, `{"text":"  正常  "}`, `{"data":{"text":"嵌套"}}`, `{}`, `null`, `not json`} {
		s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.Copy(io.Discard, r.Body); fmt.Fprint(w, body) }))
		f.current.BaseURL = ptr(s.URL)
		a.http = s.Client()
		v, e := a.Transcribe(ctx, []byte("x"), "", "")
		if e != nil {
			t.Fatal(e)
		}
		if strings.Contains(body, "正常") && (!v.OK || v.Text != "正常") {
			t.Fatal(v)
		}
		if body == `{"text":""}` && (!v.OK || v.LatencyMS != 0) {
			t.Fatal(v)
		}
		if body == `{}` && v.OK {
			t.Fatal(v)
		}
		s.Close()
	}
	if v, e := a.Transcribe(ctx, make([]byte, MaxAudioBytes+1), "", ""); e != nil || v.OK || value(v.Error) != "录音超过 8MB，请缩短后重试" {
		t.Fatal(v, e)
	}
}

func TestProbeEndpointProtocolParsingAndListShapes(t *testing.T) {
	for raw, want := range map[string]Protocol{"": OpenAI, " unknown ": OpenAI, " anthropic ": Anthropic, "ollama": Ollama} {
		if ParseProtocol(raw) != want {
			t.Fatal("protocol", raw)
		}
	}
	for base, want := range map[string]string{"https://host": "https://host/v1/models", " https://host/v1/ ": "https://host/v1/models", "https://host/prefix/v1": "https://host/prefix/v1/models"} {
		if endpoint(base, "/models") != want {
			t.Fatal("endpoint", base)
		}
	}
	for _, test := range []struct {
		protocol Protocol
		body     string
		count    int
	}{
		{OpenAI, `[{"id":"b"},{"id":"a"},{"id":"b"}]`, 2},
		{OpenAI, `{"models":[{"id":"custom","context_window":4096}]}`, 1},
		{Ollama, `{"data":[{"id":"custom","context_length":8192}]}`, 1},
	} {
		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls.Add(1)
			_, _ = io.Copy(io.Discard, r.Body)
			if test.protocol == Ollama && r.Header.Get("Authorization") != "" {
				t.Error("ollama added auth")
			}
			if strings.HasSuffix(r.URL.Path, "models") || strings.HasSuffix(r.URL.Path, "tags") {
				fmt.Fprint(w, test.body)
			} else {
				http.NotFound(w, r)
			}
		}))
		key := "synthetic"
		if test.protocol == Ollama {
			key = ""
		}
		result := NewProbe(server.Client().Transport).Models(context.Background(), ProbeTarget{test.protocol, server.URL, key}, nil)
		if !result.OK || len(result.Models) != test.count || calls.Load() > 3 {
			t.Fatal("list shape", result)
		}
		server.Close()
	}
	for _, target := range []ProbeTarget{{OpenAI, "", ""}, {OpenAI, "file:///tmp", "key"}, {OpenAI, "http://127.0.0.1:1", ""}} {
		result := NewProbe(nil).Models(context.Background(), target, nil)
		if result.OK || value(result.Kind) != "ERROR" || result.LatencyMS != 0 {
			t.Fatal("precondition", result)
		}
	}
	if target := orderedTargets([]string{"a", "b"}, nil); strings.Join(target, ",") != "a,b" {
		t.Fatal(target)
	}
	if target := orderedTargets(nil, []string{"x"}); len(target) != 0 {
		t.Fatal(target)
	}
}
func TestAudioAndConnectionHTTPFailuresCancellationAndSafeDiagnostics(t *testing.T) {
	f := &readFixture{current: Values{APIKey: ptr("synthetic-audio-secret"), AudioModel: ptr("whisper-1")}}
	read, _ := NewReadService(f, resolver(t, nil, nil))
	for _, code := range []int{400, 401, 403, 404, 413, 429, 500} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = io.Copy(io.Discard, r.Body)
			w.WriteHeader(code)
			fmt.Fprint(w, "upstream synthetic-audio-secret")
		}))
		f.current.BaseURL = ptr(server.URL)
		a := NewAudioService(read, server.Client().Transport)
		out, err := a.Transcribe(context.Background(), []byte("x"), "clip.webm", "")
		if err != nil || out.OK || out.Unavailable || out.LatencyMS != 0 || strings.Contains(value(out.Error), "synthetic-audio-secret") {
			t.Fatal("audio failure", out, err)
		}
		c := clientFor(t, server, OpenAI)
		runtime := NewRuntime(server.Client().Transport)
		runtime.Replace(c)
		result := runtime.Test(context.Background())
		if result.OK || result.Error == nil || result.Model == nil || strings.Contains(value(result.Error), "synthetic-audio-secret") {
			t.Fatal("connection failure", result)
		}
		server.Close()
	}
	a := NewAudioService(read, nil)
	if out, err := a.Transcribe(context.Background(), nil, "", ""); err != nil || value(out.Error) != "录音为空，没有可转写的内容" {
		t.Fatal(out, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	out, err := a.Transcribe(ctx, []byte("x"), "", "")
	if err != nil || value(out.Error) != "转写被中断" {
		t.Fatal("cancel", out, err)
	}
	if result := NewRuntime(nil).Test(context.Background()); result.OK || result.Error == nil || result.Model != nil {
		t.Fatal(result)
	}
}
