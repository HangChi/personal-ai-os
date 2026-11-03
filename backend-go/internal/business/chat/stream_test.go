package chat

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestEventBufferOrderingTerminalAndStickyFailure(t *testing.T) {
	var names, texts []string
	b := NewEventBuffer(func(n string, v any) error {
		names = append(names, n)
		if n == "delta" {
			texts = append(texts, v.(map[string]string)["content"])
		}
		return nil
	})
	b.interval = time.Hour
	_ = b.Publish("start", nil)
	_ = b.Publish("delta", map[string]string{"content": "你"})
	_ = b.Publish("delta", map[string]string{"content": "好"})
	_ = b.Publish("activity", nil)
	_ = b.Publish("delta", map[string]string{"content": "!"})
	_ = b.Publish("done", nil)
	_ = b.Publish("delta", map[string]string{"content": "late"})
	_ = b.Close()
	if strings.Join(names, ",") != "start,delta,activity,delta,done" || strings.Join(texts, "") != "你好!" {
		t.Fatal(names, texts)
	}
	boom := errors.New("database down")
	calls := 0
	b = NewEventBuffer(func(string, any) error { calls++; return boom })
	_ = b.Publish("delta", map[string]string{"content": "x"})
	if !errors.Is(b.Publish("done", nil), boom) || !errors.Is(b.Close(), boom) || !errors.Is(b.Publish("delta", nil), boom) || calls != 1 {
		t.Fatal("failed writes were retried", calls)
	}
}

type failedSSEWriter struct{ writes int }

func (*failedSSEWriter) Header() http.Header         { return http.Header{} }
func (*failedSSEWriter) WriteHeader(int)             {}
func (w *failedSSEWriter) Write([]byte) (int, error) { w.writes++; return 0, io.ErrClosedPipe }
func (*failedSSEWriter) Flush()                      {}

func TestSSEHeartbeatFailureAndCloseStopFutureWrites(t *testing.T) {
	recorder := httptest.NewRecorder()
	writer := &sseWriter{writer: recorder}
	if err := writer.heartbeat(); err != nil {
		t.Fatal(err)
	}
	if recorder.Body.String() != ": ping\n\n" || !recorder.Flushed {
		t.Fatal(recorder.Body.String())
	}
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Go(func() { _ = writer.heartbeat(); _ = writer.event(-1, "activity", map[string]string{"status": "OK"}) })
	}
	wg.Wait()
	writer.close()
	writer.close()
	before := recorder.Body.Len()
	if !errors.Is(writer.heartbeat(), io.ErrClosedPipe) || !errors.Is(writer.event(-1, "done", nil), io.ErrClosedPipe) || recorder.Body.Len() != before {
		t.Fatal("write after close")
	}
	broken := &failedSSEWriter{}
	writer = &sseWriter{writer: broken}
	if writer.heartbeat() == nil || writer.heartbeat() == nil || writer.event(-1, "done", nil) == nil || broken.writes != 1 {
		t.Fatal("broken SSE was retried", broken.writes)
	}
}
func TestEventBufferTimerUnicodeAndConcurrentTerminal(t *testing.T) {
	done := make(chan struct{}, 10)
	var mu sync.Mutex
	deltas := 0
	b := NewEventBuffer(func(n string, _ any) error {
		mu.Lock()
		defer mu.Unlock()
		if n == "delta" {
			deltas++
			done <- struct{}{}
		}
		return nil
	})
	b.interval = time.Millisecond
	_ = b.Publish("delta", map[string]string{"content": "timer"})
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("pending delta never flushed")
	}
	b.interval = time.Hour
	_ = b.Publish("delta", map[string]string{"content": strings.Repeat("😀", 1024)})
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("UTF16 size flush missing")
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Go(func() { _ = b.Publish("done", nil); _ = b.Close() })
	}
	wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if deltas != 2 {
		t.Fatal(deltas)
	}
}
func TestRunWatchUnsubscribeAndBroadcast(t *testing.T) {
	s := NewRuns(context.Background(), nil, nil, nil, nil, nil, nil, false)
	defer s.Close(context.Background())
	a, off := s.Watch("r")
	b, off2 := s.Watch("r")
	off()
	s.notify("r")
	select {
	case <-b:
	default:
		t.Fatal("no broadcast")
	}
	select {
	case <-a:
	default:
		t.Fatal("shared generation missing")
	}
	off2()
	_, off3 := s.Watch("r")
	off3()
	s.hubMu.Lock()
	defer s.hubMu.Unlock()
	if len(s.signals) != 0 {
		t.Fatal("subscriber leaked")
	}
}
