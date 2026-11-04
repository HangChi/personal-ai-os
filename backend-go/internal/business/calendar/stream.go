package calendar

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

const streamPath = reminderPath + "/events"

func OwnsStreamPath(path string) bool { return path == streamPath }

type Stream struct {
	events        *Events
	ctx           context.Context
	mu            sync.Mutex
	clients       map[chan []byte]struct{}
	wakeup        chan struct{}
	done          chan struct{}
	cancel        context.CancelFunc
	snapshots     atomic.Uint64
	readFailures  atomic.Uint64
	writeFailures atomic.Uint64
}

func NewStream(ctx context.Context, events *Events, interval time.Duration) *Stream {
	ctx, cancel := context.WithCancel(ctx)
	stream := &Stream{events: events, ctx: ctx, cancel: cancel, clients: make(map[chan []byte]struct{}), wakeup: make(chan struct{}, 1), done: make(chan struct{})}
	go stream.poll(interval)
	return stream
}

func (s *Stream) Close() {
	s.cancel()
	<-s.done
}

func (s *Stream) Notify() {
	select {
	case s.wakeup <- struct{}{}:
	default:
	}
}

func (s *Stream) poll(interval time.Duration) {
	defer close(s.done)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.wakeup:
		case <-ticker.C:
		}
		s.mu.Lock()
		active := len(s.clients)
		s.mu.Unlock()
		if active == 0 {
			continue
		}
		items, err := s.events.Reminders(s.ctx, "PENDING", true, 500, time.Now())
		if err != nil {
			s.readFailures.Add(1)
			continue
		}
		snapshot, err := json.Marshal(items)
		if err != nil {
			continue
		}
		s.mu.Lock()
		for client := range s.clients {
			select {
			case <-client:
			default:
			}
			client <- snapshot
		}
		s.mu.Unlock()
	}
}

func (s *Stream) Serve(w http.ResponseWriter, r *http.Request) error {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return web.MethodNotSupported(r.Method, []string{"GET"})
	}
	if _, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"text/event-stream"}); !ok {
		return web.NotAcceptable()
	}
	client := make(chan []byte, 1)
	s.mu.Lock()
	if s.ctx.Err() != nil || len(s.clients) >= 32 {
		s.mu.Unlock()
		return api.NewBusinessError(api.RateLimited, "提醒连接已满，请稍后重试")
	}
	s.clients[client] = struct{}{}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.clients, client)
		s.mu.Unlock()
	}()
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	if r.Method == http.MethodHead {
		return nil
	}
	response := http.NewResponseController(w)
	if err := response.Flush(); err != nil {
		return nil
	}
	s.Notify()
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	heartbeat := time.NewTimer(15 * time.Second)
	defer heartbeat.Stop()
	var previous []byte
	for {
		var event string
		var changed bool
		select {
		case <-ctx.Done():
			return nil
		case <-s.ctx.Done():
			return nil
		case snapshot := <-client:
			if bytes.Equal(previous, snapshot) {
				continue
			}
			previous = snapshot
			changed = true
			event = "event:reminders\ndata:" + string(snapshot) + "\n\n"
		case <-heartbeat.C:
			event = ":ping\n\n"
		}
		_ = response.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, err := fmt.Fprint(w, event); err != nil {
			s.writeFailures.Add(1)
			return nil
		}
		if err := response.Flush(); err != nil {
			s.writeFailures.Add(1)
			return nil
		}
		if changed {
			s.snapshots.Add(1)
		}
		_ = response.SetWriteDeadline(time.Time{})
		heartbeat.Reset(15 * time.Second)
	}
}

type StreamStats struct {
	Connections   int
	Snapshots     uint64
	ReadFailures  uint64
	WriteFailures uint64
}

func (s *Stream) Stats() StreamStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return StreamStats{len(s.clients), s.snapshots.Load(), s.readFailures.Load(), s.writeFailures.Load()}
}
