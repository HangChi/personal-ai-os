package calendar

import (
	"bufio"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

func TestReminderStreamSharesSnapshotsAndRecovers(t *testing.T) {
	var value atomic.Pointer[[]Reminder]
	empty := []Reminder{}
	value.Store(&empty)
	var reads atomic.Int32
	var fail atomic.Bool
	stream := newStream(context.Background(), time.Hour, func(context.Context) ([]Reminder, error) {
		reads.Add(1)
		if fail.Load() {
			return nil, errors.New("fixture database unavailable")
		}
		return *value.Load(), nil
	})
	defer stream.Close()
	stream.heartbeatEvery = 20 * time.Millisecond
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := stream.Serve(w, r); err != nil {
			t.Error(err)
		}
	}))
	defer server.Close()
	client := server.Client()
	client.Timeout = 3 * time.Second
	open := func() *http.Response {
		t.Helper()
		response, err := client.Get(server.URL)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = response.Body.Close() })
		return response
	}
	expect := func(reader *bufio.Reader, prefix string) string {
		t.Helper()
		for {
			line, err := reader.ReadString('\n')
			if err != nil {
				t.Fatal(err)
			}
			if strings.HasPrefix(line, prefix) {
				return line
			}
		}
	}
	a := open()
	defer a.Body.Close()
	readerA := bufio.NewReader(a.Body)
	expect(readerA, "data:[]")
	b := open()
	defer b.Body.Close()
	readerB := bufio.NewReader(b.Body)
	expect(readerB, "data:[]")
	expect(readerA, ":ping")
	before := reads.Load()
	fail.Store(true)
	stream.Notify()
	deadline := time.Now().Add(time.Second)
	for stream.Stats().ReadFailures == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if stream.Stats().ReadFailures != 1 {
		t.Fatal("read failure was not counted")
	}
	expect(readerA, ":ping")
	fail.Store(false)
	value.Store(&[]Reminder{{ID: "fixture", Title: "shared"}})
	stream.Notify()
	for _, reader := range []*bufio.Reader{readerA, readerB} {
		if line := expect(reader, "data:"); !strings.Contains(line, `"title":"shared"`) {
			t.Fatalf("snapshot = %s", line)
		}
	}
	if got := reads.Load() - before; got != 2 {
		t.Fatalf("read attempts = %d, want one failed and one successful for both connections", got)
	}
	c := open()
	defer c.Body.Close()
	if line := expect(bufio.NewReader(c.Body), "data:"); !strings.Contains(line, `"title":"shared"`) {
		t.Fatalf("reconnect snapshot = %s", line)
	}
}

type discardStreamWriter struct{ header http.Header }

func (w discardStreamWriter) Header() http.Header       { return w.header }
func (discardStreamWriter) WriteHeader(int)             {}
func (discardStreamWriter) Write(p []byte) (int, error) { return len(p), nil }
func (discardStreamWriter) Flush()                      {}

func TestReminderStreamAdmissionAndShutdown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := newStream(ctx, time.Hour, func(context.Context) ([]Reminder, error) { return []Reminder{}, nil })
	defer stream.Close()
	var clients sync.WaitGroup
	for range 32 {
		clients.Go(func() {
			err := stream.Serve(discardStreamWriter{make(http.Header)}, httptest.NewRequestWithContext(ctx, "GET", "/", nil))
			if err != nil {
				t.Error(err)
			}
		})
	}
	deadline := time.Now().Add(time.Second)
	for stream.Stats().Connections != 32 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if stream.Stats().Connections != 32 {
		t.Fatal("expected 32 admitted streams")
	}
	reject := func() {
		t.Helper()
		err := stream.Serve(httptest.NewRecorder(), httptest.NewRequest("GET", "/", nil))
		var problem *api.BusinessError
		if !errors.As(err, &problem) || problem.Code() != api.RateLimited {
			t.Fatalf("rejection = %v", err)
		}
	}
	reject()
	stream.Close()
	clients.Wait()
	if stream.Stats().Connections != 0 {
		t.Fatal("shutdown leaked stream slots")
	}
	reject()
}
