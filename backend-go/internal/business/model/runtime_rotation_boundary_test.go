package model

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestRuntimeRepeatedConfigurationRotationIsBounded(t *testing.T) {
	current := &ChatClient{resolved: Resolved{ChatModel: "current"}}
	alternate := &ChatClient{resolved: Resolved{ChatModel: "alternate"}}
	runtime := NewRuntime(nil)
	runtime.Replace(current)
	calls := 0
	runtime.Register(func(context.Context, string) (*ChatClient, error) {
		calls++
		runtime.Replace(current)
		return alternate, nil
	})
	result, err := runtime.Target(context.Background(), "alternate")
	if err != nil || result != current || calls != 2 {
		t.Fatal(result, err, calls)
	}
	calls = 0
	if _, _, err = runtime.TargetSnapshot(context.Background(), "alternate"); err == nil || calls != 6 {
		t.Fatal(err, calls)
	}
	runtime.Register(nil)
	if result, err = runtime.Target(context.Background(), "missing"); err != nil || result != current {
		t.Fatal(result, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	runtime.Register(func(context.Context, string) (*ChatClient, error) { cancel(); return nil, context.Canceled })
	if _, err = runtime.Target(ctx, "missing"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestConnectionAndTranscriptionCancellationPreserveReasons(t *testing.T) {
	resolved := resolver(t, nil, nil).Resolve(Values{BaseURL: ptr("https://fixture.invalid"), APIKey: ptr("synthetic"), ChatModel: ptr("chat")})
	for _, sentinel := range []error{context.Canceled, context.DeadlineExceeded} {
		runtime := NewRuntime(nil)
		client, err := NewChatClient(resolved, OpenAI, boundaryTransport(func(*http.Request) (*http.Response, error) { return nil, sentinel }))
		if err != nil {
			t.Fatal(err)
		}
		runtime.Replace(client)
		ctx, cancel := context.WithCancel(context.Background())
		if sentinel == context.Canceled {
			cancel()
		} else {
			cancel()
			ctx, cancel = context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
		}
		result := runtime.Test(ctx)
		cancel()
		expected := "超时"
		if sentinel == context.Canceled {
			expected = "中断"
			if result.LatencyMS != 0 {
				t.Fatal(result)
			}
		}
		if result.OK || result.Error == nil || !strings.Contains(*result.Error, expected) {
			t.Fatal(result)
		}
	}
	if got := truncateUTF16("ab😀cd", 3); got != "ab?…" {
		t.Fatal(got)
	}
	if got := truncateUTF16("ab😀cd", 4); got != "ab😀…" {
		t.Fatal(got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if got := transcriptionNetwork(ctx); got.Error == nil || !strings.Contains(*got.Error, "中断") {
		t.Fatal(got)
	}
	deadline, stop := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer stop()
	if got := transcriptionNetwork(deadline); got.Error == nil || !strings.Contains(*got.Error, "超时") {
		t.Fatal(got)
	}
}
