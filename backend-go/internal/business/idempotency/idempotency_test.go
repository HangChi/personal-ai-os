package idempotency

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type memoryStore struct {
	mu      sync.Mutex
	records map[string]Record
	block   chan struct{}
}

func newMemoryStore() *memoryStore { return &memoryStore{records: make(map[string]Record)} }
func (s *memoryStore) Find(_ context.Context, key string) (Record, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.records[key]
	return r, ok, nil
}
func (s *memoryStore) Claim(_ context.Context, record Record) (Record, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if winner, ok := s.records[record.Key]; ok {
		return winner, false, nil
	}
	s.records[record.Key] = record
	return record, true, nil
}
func (s *memoryStore) DeleteStaleHTTP(_ context.Context, record Record, threshold time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	current, ok := s.records[record.Key]
	if ok && current.Source == SourceHTTP && current.Outcome == InProgress && current.Digest == record.Digest && current.CreatedAt.Equal(record.CreatedAt) && current.CreatedAt.Before(threshold) {
		delete(s.records, record.Key)
		return true, nil
	}
	return false, nil
}
func (s *memoryStore) Complete(_ context.Context, key string, outcome Outcome, payload *string, duration int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.records[key]
	if !ok || r.Outcome != InProgress {
		return errors.New("missing placeholder")
	}
	r.Outcome, r.ResponsePayload, r.DurationMS = outcome, payload, &duration
	s.records[key] = r
	return nil
}

func request(key string, source Source, digest string) Request {
	return Request{Key: key, Source: source, Operation: "POST /api/v1/tasks", Digest: digest}
}

func TestCanonicalJSONDigestAndToolKey(t *testing.T) {
	a := map[string]any{"z": "<中文>", "a": []any{1, true, nil}}
	b := map[string]any{"a": []any{1, true, nil}, "z": "<中文>"}
	first, err := CanonicalJSON(a)
	if err != nil || string(first) != `{"a":[1,true,null],"z":"<中文>"}` {
		t.Fatalf("canonical=%s err=%v", first, err)
	}
	d1, _ := Digest(a)
	d2, _ := Digest(b)
	if d1 != d2 || len(d1) != 64 {
		t.Fatalf("digests differ: %s %s", d1, d2)
	}
	if ToolKey("c", "t", "create", d1) != SHA256("c|t|create|"+d1) {
		t.Fatal("tool key scope changed")
	}
}

func TestExecuteReplaysCompletedResultAndRejectsDigestConflict(t *testing.T) {
	store := newMemoryStore()
	service, _ := NewService(store)
	digest := SHA256("same")
	payload := `{"id":"1"}`
	var calls atomic.Int32
	action := func(context.Context) (Result, error) { calls.Add(1); return NewResult(&payload, Success), nil }
	first, err := service.Execute(context.Background(), request("key", SourceHTTP, digest), action)
	if err != nil || first.Replayed {
		t.Fatalf("first=%+v err=%v", first, err)
	}
	second, err := service.Execute(context.Background(), request("key", SourceHTTP, digest), action)
	if err != nil || !second.Replayed || second.Outcome != Success || *second.ResponsePayload != payload || calls.Load() != 1 {
		t.Fatalf("second=%+v calls=%d err=%v", second, calls.Load(), err)
	}
	_, err = service.Execute(context.Background(), request("key", SourceHTTP, SHA256("different")), action)
	var conflict *ConflictError
	if !errors.As(err, &conflict) || conflict.Kind != ErrDigestConflict || calls.Load() != 1 {
		t.Fatalf("conflict=%v calls=%d", err, calls.Load())
	}
}

func TestConcurrentClaimExecutesActionOnce(t *testing.T) {
	store := newMemoryStore()
	service, _ := NewService(store)
	digest := SHA256("same")
	started := make(chan struct{})
	release := make(chan struct{})
	var calls atomic.Int32
	action := func(context.Context) (Result, error) {
		calls.Add(1)
		close(started)
		<-release
		return NewResult(nil, Success), nil
	}
	firstDone := make(chan error, 1)
	go func() {
		_, err := service.Execute(context.Background(), request("race", SourceHTTP, digest), action)
		firstDone <- err
	}()
	<-started
	_, err := service.Execute(context.Background(), request("race", SourceHTTP, digest), action)
	var conflict *ConflictError
	if !errors.As(err, &conflict) || conflict.Kind != ErrInProgress {
		t.Fatalf("concurrent err=%v", err)
	}
	close(release)
	if err := <-firstDone; err != nil || calls.Load() != 1 {
		t.Fatalf("first err=%v calls=%d", err, calls.Load())
	}
}

func TestStaleHTTPRetriesButStaleToolRemainsUnknown(t *testing.T) {
	store := newMemoryStore()
	service, _ := NewService(store)
	now := time.Date(2026, 11, 3, 1, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return now }
	digest := SHA256("same")
	store.records["http"] = Record{Key: "http", Source: SourceHTTP, Operation: "op", Digest: digest, Outcome: InProgress, CreatedAt: now.Add(-StaleAfter - time.Second)}
	var calls atomic.Int32
	_, err := service.Execute(context.Background(), request("http", SourceHTTP, digest), func(context.Context) (Result, error) { calls.Add(1); return NewResult(nil, Success), nil })
	if err != nil || calls.Load() != 1 {
		t.Fatalf("stale HTTP err=%v calls=%d", err, calls.Load())
	}
	store.records["tool"] = Record{Key: "tool", Source: SourceTool, Operation: "op", Digest: digest, Outcome: InProgress, CreatedAt: now.Add(-24 * time.Hour)}
	_, err = service.Execute(context.Background(), request("tool", SourceTool, digest), func(context.Context) (Result, error) { calls.Add(1); return NewResult(nil, Success), nil })
	var conflict *ConflictError
	if !errors.As(err, &conflict) || conflict.Kind != ErrInProgress || calls.Load() != 1 {
		t.Fatalf("stale TOOL err=%v calls=%d", err, calls.Load())
	}
}

func TestFailureIsPersistedAndNeverReplayedAsSuccess(t *testing.T) {
	store := newMemoryStore()
	service, _ := NewService(store)
	digest := SHA256("same")
	boom := errors.New("boom")
	_, err := service.Execute(context.Background(), request("failed", SourceHTTP, digest), func(context.Context) (Result, error) { return Result{}, boom })
	if !errors.Is(err, boom) || store.records["failed"].Outcome != Failed {
		t.Fatalf("first err=%v record=%+v", err, store.records["failed"])
	}
	_, err = service.Execute(context.Background(), request("failed", SourceHTTP, digest), func(context.Context) (Result, error) { t.Fatal("must not retry"); return Result{}, nil })
	var conflict *ConflictError
	if !errors.As(err, &conflict) || conflict.Kind != ErrPreviousFailed {
		t.Fatalf("replay err=%v", err)
	}
}
