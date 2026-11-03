package health

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testOptions() Options {
	return Options{Application: "personal-ai-os", Version: "0.1.0-SNAPSHOT", ProbeTimeout: time.Second}
}

func TestJavaHealthServiceDependencyComposition(t *testing.T) {
	for _, row := range []struct {
		name                 string
		err                  error
		configured           bool
		status, database, ai string
	}{
		{"allReady", nil, true, "UP", "UP", "CONFIGURED"},
		{"databaseUnavailable", errors.New("password=fixture-secret query=private"), true, "DOWN", "DOWN", "CONFIGURED"},
		{"modelUnconfigured", nil, false, "UP", "UP", "NOT_CONFIGURED"},
	} {
		t.Run(row.name, func(t *testing.T) {
			var log bytes.Buffer
			calls, aiCalls := 0, 0
			service, err := New(testOptions(), func(context.Context) error { calls++; return row.err }, func() bool { aiCalls++; return row.configured }, slog.New(slog.NewTextHandler(&log, nil)))
			if err != nil {
				t.Fatal(err)
			}
			before := time.Now()
			status := service.CurrentStatus(context.Background())
			if status.Status != row.status || status.Components["database"] != row.database || status.Components["ai"] != row.ai || len(status.Components) != 2 {
				t.Fatal("依赖状态合成不符", status)
			}
			if status.Application != "personal-ai-os" || status.Version != "0.1.0-SNAPSHOT" || status.Timestamp.Before(before) || status.Timestamp.After(time.Now()) || status.Timestamp.Location() != time.UTC || calls != 1 || aiCalls != 1 {
				t.Fatal("元数据/UTC/调用次数不符")
			}
			if strings.Contains(log.String(), "fixture-secret") || strings.Contains(log.String(), "private") {
				t.Fatal("健康日志泄漏依赖错误")
			}
			status.Components["database"] = "tampered"
			if service.CurrentStatus(context.Background()).Components["database"] != row.database {
				t.Fatal("返回对象共享可变状态")
			}
		})
	}
}

func TestHealthProbeDeadlineAndCancellation(t *testing.T) {
	for _, parentFirst := range []bool{false, true} {
		options := testOptions()
		options.ProbeTimeout = 60 * time.Millisecond
		ctx := context.Background()
		if parentFirst {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, 15*time.Millisecond)
			defer cancel()
		}
		var observed time.Time
		service, _ := New(options, func(ctx context.Context) error {
			observed, _ = ctx.Deadline()
			<-ctx.Done()
			return ctx.Err()
		}, func() bool { return false }, slog.Default())
		start := time.Now()
		status := service.CurrentStatus(ctx)
		if status.Status != "DOWN" || observed.IsZero() || time.Since(start) > time.Second {
			t.Fatal("探测未遵守取消/期限")
		}
		if parentFirst {
			deadline, _ := ctx.Deadline()
			if !observed.Equal(deadline) {
				t.Fatal("覆盖了更早父期限")
			}
		}
	}
}

func TestHealthConstructionAndConcurrentIndependentReports(t *testing.T) {
	probe := func(context.Context) error { return nil }
	ai := func() bool { return true }
	options := testOptions()
	if _, err := New(options, nil, ai, slog.Default()); err == nil {
		t.Fatal("缺失数据库依赖")
	}
	if _, err := New(options, probe, nil, slog.Default()); err == nil {
		t.Fatal("缺失模型状态依赖")
	}
	if _, err := New(options, probe, ai, nil); err == nil {
		t.Fatal("缺失日志依赖")
	}
	options.ProbeTimeout = 0
	if _, err := New(options, probe, ai, slog.Default()); err == nil {
		t.Fatal("未限制探测时间")
	}
	options = testOptions()
	var calls atomic.Int32
	service, _ := New(options, func(context.Context) error { calls.Add(1); return nil }, ai, slog.Default())
	var group sync.WaitGroup
	for i := 0; i < 100; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			status := service.CurrentStatus(context.Background())
			status.Components["database"] = "local"
		}()
	}
	group.Wait()
	if calls.Load() != 100 {
		t.Fatal("探测次数被缓存或遗漏")
	}
}
