package calendar

import (
	"context"
	"time"
)

func RunFixedDelay(ctx context.Context, initial, interval time.Duration, run func(context.Context, time.Time)) {
	timer := time.NewTimer(initial)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-timer.C:
			if ctx.Err() != nil {
				return
			}
			run(ctx, now)
			timer.Reset(interval)
		}
	}
}
