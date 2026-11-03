package model

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

func runtimeClient(t *testing.T, base, name string) *ChatClient {
	t.Helper()
	c, e := NewChatClient(resolver(t, nil, nil).Resolve(Values{BaseURL: ptr(base), APIKey: ptr("synthetic-runtime-key"), ChatModel: &name}), OpenAI, nil)
	if e != nil {
		t.Fatal(e)
	}
	return c
}
func TestRuntimeNamedSingleFlightGenerationBoundsAndFallback(t *testing.T) {
	r := NewRuntime(nil)
	if _, e := r.Target(context.Background(), ""); e == nil {
		t.Fatal("unconfigured accepted")
	}
	first := runtimeClient(t, "https://first.invalid", "default")
	r.Replace(first)
	var creates atomic.Int32
	r.Register(func(ctx context.Context, name string) (*ChatClient, error) {
		creates.Add(1)
		if name == "bad" {
			return nil, errors.New("offline")
		}
		return runtimeClient(t, "https://first.invalid", name), nil
	})
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, e := r.Target(context.Background(), "historical-not-in-saved-list")
			if e != nil || c.Name() != "historical-not-in-saved-list" {
				t.Error("session override lost", e)
			}
		}()
	}
	wg.Wait()
	if creates.Load() != 1 {
		t.Fatal("duplicate construction", creates.Load())
	}
	c, e := r.Target(context.Background(), "bad")
	if e != nil || c != first {
		t.Fatal("failure didn't fallback")
	}
	for i := 0; i < 40; i++ {
		_, _ = r.Target(context.Background(), fmt.Sprint(i))
	}
	if len(r.named) > 32 {
		t.Fatal("unbounded model cache")
	}
	started, release := make(chan struct{}), make(chan struct{})
	var n atomic.Int32
	r.Register(func(ctx context.Context, name string) (*ChatClient, error) {
		if n.Add(1) == 1 {
			close(started)
			<-release
			return runtimeClient(t, "https://first.invalid", name), nil
		}
		return runtimeClient(t, "https://second.invalid", name), nil
	})
	done := make(chan *ChatClient, 1)
	go func() { c, _ := r.Target(context.Background(), "chosen"); done <- c }()
	<-started
	r.Replace(runtimeClient(t, "https://second.invalid", "default"))
	close(release)
	c = <-done
	if c.resolved.BaseURL != "https://second.invalid" || n.Load() != 2 {
		t.Fatal("cached previous endpoint in new generation")
	}
	v := resolver(t, nil, nil).Resolve(Values{})
	if e = r.Apply(v); e != nil || r.Current().resolved.BaseURL != "https://second.invalid" {
		t.Fatal("no-key apply replaced existing model")
	}
}
func TestWindowEvidenceLowerBoundsLimitsAndEpochIsolation(t *testing.T) {
	e := NewWindowEvidence()
	epoch := e.Generation()
	e.Accepted(epoch, "model", 100, 1000)
	v := e.Snapshot(epoch, "model")
	if v.TokenRatio != 1 || v.VerifiedInputTokens != 1000 || v.ContextTokens != nil {
		t.Fatal("success claimed upper bound", v)
	}
	e.Accepted(epoch, "model", 1000, 2000)
	e.Accepted(epoch, "model", 1000, 800)
	if e.Snapshot(epoch, "model").TokenRatio != 2 {
		t.Fatal("token ratio became less conservative")
	}
	e.Rejected(epoch, "model", errors.New("maximum context length is 32,768; maximum input tokens is 30,720"), 40000)
	v = e.Snapshot(epoch, "model")
	if valueInt(v.ContextTokens) != 32768 || valueInt(v.InputTokens) != 30720 || !v.ExplicitInputLimit {
		t.Fatal(v)
	}
	e.Rejected(epoch, "model", errors.New("input length should be [1, 8192]"), 20000)
	if valueInt(e.Snapshot(epoch, "model").InputTokens) != 8192 {
		t.Fatal("didn't retain smaller endpoint limit")
	}
	e.Rejected(epoch, "other", errors.New("prompt is too long"), 10000)
	if v = e.Snapshot(epoch, "other"); valueInt(v.InputTokens) != 6500 || v.ExplicitInputLimit {
		t.Fatal(v)
	}
	e.Reset()
	e.Accepted(epoch, "model", 1000, 4000)
	e.Rejected(epoch, "model", errors.New("maximum context length 4096"), 10000)
	if e.Snapshot(e.Generation(), "model").VerifiedInputTokens != 0 {
		t.Fatal("stale response leaked evidence")
	}
}
