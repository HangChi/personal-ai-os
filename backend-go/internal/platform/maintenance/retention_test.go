package maintenance

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

type retentionStore struct {
	usage, operations []time.Time
	err               error
}

func (s *retentionStore) DeleteUsage(_ context.Context, cutoff time.Time) (int64, error) {
	s.usage = append(s.usage, cutoff)
	return 2, s.err
}
func (s *retentionStore) DeleteOperations(_ context.Context, cutoff time.Time) (int64, error) {
	s.operations = append(s.operations, cutoff)
	return 3, s.err
}

func TestP8RetentionPermanentAndIndependentCutoffs(t *testing.T) {
	now := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	for _, tc := range [][2]int{{0, 0}, {180, 30}, {0, 30}, {30, 0}, {2147483647, 2147483647}} {
		store := &retentionStore{}
		s, err := New(store, config.RetentionProperties{TokenUsageDays: tc[0], OperationRecordDays: tc[1]}, func() time.Time { return now })
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.Cleanup(context.Background()); err != nil {
			t.Fatal(err)
		}
		for i, calls := range [][]time.Time{store.usage, store.operations} {
			if tc[i] == 0 && len(calls) != 0 || tc[i] > 0 && (len(calls) != 1 || !calls[0].Equal(now.AddDate(0, 0, -tc[i]))) {
				t.Fatal(tc, calls)
			}
		}
	}
	for _, tc := range [][2]int{{-1, 0}, {0, -1}} {
		if _, err := New(&retentionStore{}, config.RetentionProperties{TokenUsageDays: tc[0], OperationRecordDays: tc[1]}, nil); err == nil {
			t.Fatal("negative accepted")
		}
	}
}

func TestP8RetentionFailureStopsNextPolicy(t *testing.T) {
	store := &retentionStore{err: errors.New("synthetic")}
	s, _ := New(store, config.RetentionProperties{TokenUsageDays: 1, OperationRecordDays: 1}, nil)
	if _, err := s.Cleanup(context.Background()); err == nil || len(store.operations) != 0 {
		t.Fatal(err, store.operations)
	}
}

func TestRetentionRejectsMissingDependenciesAndCancelledWork(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if _, err := New(nil, config.RetentionProperties{}, nil); err == nil {
		t.Fatal("nil store accepted")
	}
	service, _ := New(&retentionStore{}, config.RetentionProperties{TokenUsageDays: 1}, nil)
	if _, err := service.Cleanup(nil); err == nil {
		t.Fatal("nil context accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := service.Cleanup(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		ctx    context.Context
		logger *slog.Logger
		store  Store
	}{
		{nil, logger, &retentionStore{}}, {context.Background(), nil, &retentionStore{}}, {context.Background(), logger, nil},
	} {
		if _, err := Start(tc.ctx, config.RetentionProperties{Cron: "* * * * * *"}, tc.store, tc.logger); err == nil {
			t.Fatal("missing dependency accepted")
		}
	}
	for _, store := range []*PostgresStore{nil, NewPostgresStore(nil)} {
		if _, err := store.DeleteUsage(context.Background(), time.Now()); err == nil {
			t.Fatal("nil database accepted")
		}
	}
}

func TestP8RetentionCronDisableCancellationAndDeduplication(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, expression := range []string{"-", "0 30 4 * * *"} {
		ctx, cancel := context.WithCancel(context.Background())
		worker, err := Start(ctx, config.RetentionProperties{TokenUsageDays: 1, Cron: expression}, &retentionStore{}, logger)
		if err != nil {
			t.Fatal(err)
		}
		cancel()
		worker.Close()
		worker.Close()
	}
	if _, err := Start(context.Background(), config.RetentionProperties{Cron: "invalid"}, &retentionStore{}, logger); err == nil {
		t.Fatal("invalid cron accepted")
	}
	cron, err := ParseCron("0 30 4 * NOV WED")
	if err != nil {
		t.Fatal(err)
	}
	ticks := make(chan time.Time, 4)
	now := time.Date(2026, 11, 4, 4, 30, 0, 0, time.UTC)
	ticks <- now.Add(-time.Second)
	ticks <- now
	ticks <- now.Add(10 * time.Millisecond)
	ticks <- now.Add(24 * time.Hour)
	close(ticks)
	var calls atomic.Int32
	run(context.Background(), cron, time.UTC, ticks, func(context.Context) { calls.Add(1) })
	if calls.Load() != 1 {
		t.Fatal(calls.Load())
	}
}
