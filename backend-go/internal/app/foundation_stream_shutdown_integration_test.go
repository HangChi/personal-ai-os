//go:build integration

package app

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestReminderStreamClosesBeforeGracefulHTTPShutdown(t *testing.T) {
	f := chatFixtureFoundation(t, map[string]string{
		"spring.ai.model.chat":            "none",
		"spring.ai.openai.base-url":       "http://127.0.0.1:1",
		"app.calendar.scan-enabled":       "false",
		"app.memory.backfill-enabled":     "false",
		"app.context.auto-memory.enabled": "false",
	}, nil, true)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- f.Serve(ctx, listener, nil, 500*time.Millisecond) }()
	client := &http.Client{Timeout: 5 * time.Second}
	defer client.CloseIdleConnections()
	response, err := client.Get("http://" + listener.Addr().String() + "/api/v1/reminders/events")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("reminder stream status = %d", response.StatusCode)
	}
	scanner := bufio.NewScanner(response.Body)
	received := false
	for scanner.Scan() {
		if strings.HasPrefix(scanner.Text(), "data:") {
			received = true
			break
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if !received {
		t.Fatal("stream ended without its initial reminder snapshot")
	}
	if f.p5.stream.Stats().Connections != 1 {
		t.Fatal("test requires a real, active reminder stream")
	}
	// Keep the response open while the actual HTTP server gracefully stops.
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("reminder SSE consumed the HTTP shutdown budget: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("reminder stream prevented HTTP shutdown")
	}
	if _, err := io.ReadAll(response.Body); err != nil {
		t.Fatalf("stream should end cleanly: %v", err)
	}
	if f.p5.stream.Stats().Connections != 0 {
		t.Fatal("shutdown leaked reminder connections")
	}
	if err := f.Pool().Ping(context.Background()); err == nil {
		t.Fatal("shutdown did not close the owned database pool")
	}
	rebound, err := net.Listen("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal("shutdown did not release the listener", err)
	}
	rebound.Close()
}
