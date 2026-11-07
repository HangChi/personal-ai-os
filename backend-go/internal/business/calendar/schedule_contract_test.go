package calendar

import (
	"context"
	"testing"
	"time"
)

func TestFixedDelayRunsSeriallyAndStopsOnCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	calls := 0
	done := make(chan struct{})
	go func() {
		defer close(done)
		RunFixedDelay(ctx, 0, time.Millisecond, func(got context.Context, at time.Time) {
			calls++
			if got != ctx || at.IsZero() {
				t.Error("invalid invocation")
			}
			if calls == 2 {
				cancel()
			}
		})
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("scheduler did not terminate")
	}
	if calls != 2 {
		t.Fatal(calls)
	}
	cancelled, stop := context.WithCancel(context.Background())
	stop()
	RunFixedDelay(cancelled, time.Hour, time.Hour, func(context.Context, time.Time) { t.Error("cancelled callback ran") })
}
