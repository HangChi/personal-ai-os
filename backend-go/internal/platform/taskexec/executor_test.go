package taskexec

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func testExecutor(t *testing.T, options Options) *Executor {
	t.Helper()
	executor, err := New(options, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { executor.Stop(); wait(t, executor) })
	return executor
}

func wait(t *testing.T, executor *Executor) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := executor.Wait(ctx); err != nil {
		t.Fatal("后台执行器未结束", err)
	}
}

func receive[T any](t *testing.T, values <-chan T) T {
	t.Helper()
	select {
	case value := <-values:
		return value
	case <-time.After(2 * time.Second):
		t.Fatal("任务未及时执行")
		var zero T
		return zero
	}
}

func TestJavaBoundedQueueExpansionAndDiscardPolicy(t *testing.T) {
	options := DefaultOptions()
	if options.CoreWorkers != 1 || options.MaxWorkers != 2 || options.QueueCapacity != 32 || options.IdleTimeout != time.Minute {
		t.Fatal("原 Java 默认值不符")
	}
	executor := testExecutor(t, options)
	started := make(chan struct{}, 2)
	release := make(chan struct{})
	defer close(release)
	blocking := func(ctx context.Context) error {
		started <- struct{}{}
		select {
		case <-release:
		case <-ctx.Done():
		}
		return nil
	}
	if !executor.Submit(context.Background(), blocking) {
		t.Fatal("首任务被拒")
	}
	receive(t, started)
	var queued atomic.Int32
	for i := 0; i < 32; i++ {
		if !executor.Submit(context.Background(), func(context.Context) error { queued.Add(1); return nil }) {
			t.Fatal("队列提前满")
		}
	}
	if state := executor.Stats(); state.Workers != 1 || state.Queued != 32 {
		t.Fatal("队列未满前不应扩展线程", state)
	}
	if !executor.Submit(context.Background(), blocking) {
		t.Fatal("满队列应扩展到第二个执行单元")
	}
	receive(t, started)
	if executor.Submit(context.Background(), func(context.Context) error { t.Error("满载任务被执行"); return nil }) {
		t.Fatal("满载未丢弃")
	}
	executor.Shutdown()
	if executor.Submit(context.Background(), blocking) {
		t.Fatal("关闭后接受任务")
	}
	// 保持两个任务阻塞，有限 Wait 不得伪装已完成或取消正在排空的任务。
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Millisecond)
	if !errors.Is(executor.Wait(ctx), context.DeadlineExceeded) {
		t.Fatal("提前报告排空完成")
	}
	cancel()
	release <- struct{}{}
	release <- struct{}{}
	wait(t, executor)
	state := executor.Stats()
	if queued.Load() != 32 || state.Completed != 34 || state.Accepted != 34 || state.Rejected != 2 || state.Workers != 0 || state.Active != 0 || state.Queued != 0 || !state.Shutdown {
		t.Fatal("排空/计数不符", state)
	}
}

type sourceKey struct{}

func requestContext(id string) context.Context {
	var result context.Context
	handler := httpmiddleware.RequestIDMiddleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { result = r.Context() }))
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set(httpmiddleware.RequestIDHeader, id)
	handler.ServeHTTP(httptest.NewRecorder(), r)
	return result
}

func TestJavaRequestIDCaptureAndNoCrossTaskLeak(t *testing.T) {
	options := DefaultOptions()
	options.MaxWorkers = 1
	executor := testExecutor(t, options)
	values := make(chan string, 3)
	ctx, cancel := context.WithCancel(context.WithValue(requestContext("trace-42"), sourceKey{}, "request-secret"))
	cancel()
	for _, source := range []context.Context{ctx, context.Background(), requestContext("trace-next")} {
		if !executor.Submit(source, func(ctx context.Context) error {
			if ctx.Err() != nil || ctx.Value(sourceKey{}) != nil {
				t.Error("后台任务携带了HTTP取消或非诊断请求状态")
			}
			values <- httpmiddleware.RequestID(ctx)
			return nil
		}) {
			t.Fatal("任务未接受")
		}
	}
	for _, want := range []string{"trace-42", "", "trace-next"} {
		if got := receive(t, values); got != want {
			t.Fatalf("请求标识串任务: %q != %q", got, want)
		}
	}
	executor.Shutdown()
	wait(t, executor)
}

func TestStopCancelsActiveAndDiscardsQueued(t *testing.T) {
	options := DefaultOptions()
	options.MaxWorkers = 1
	executor := testExecutor(t, options)
	started := make(chan struct{})
	executor.Submit(context.Background(), func(ctx context.Context) error { close(started); <-ctx.Done(); return ctx.Err() })
	receive(t, started)
	for i := 0; i < 10; i++ {
		executor.Submit(context.Background(), func(context.Context) error { t.Error("强制停止后运行队列任务"); return nil })
	}
	if dropped := executor.Stop(); dropped != 10 {
		t.Fatal("未丢弃待运行任务", dropped)
	}
	if executor.Stop() != 0 {
		t.Fatal("重复停止重复丢弃")
	}
	wait(t, executor)
	state := executor.Stats()
	if !state.Stopped || state.Discarded != 10 || state.Completed != 1 || state.Workers != 0 {
		t.Fatal("停止统计不符", state)
	}
}

func TestTaskFailureAndPanicDoNotStopQueueOrLeakDetails(t *testing.T) {
	var logs bytes.Buffer
	options := DefaultOptions()
	options.MaxWorkers = 1
	executor, err := New(options, slog.New(slog.NewTextHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { executor.Stop(); wait(t, executor) })
	executor.Submit(requestContext("failure-trace"), func(context.Context) error { return errors.New("password=fixture-secret") })
	executor.Submit(requestContext("panic-trace"), func(context.Context) error { panic("private-request-body") })
	var last atomic.Bool
	executor.Submit(context.Background(), func(context.Context) error { last.Store(true); return nil })
	executor.Shutdown()
	wait(t, executor)
	state := executor.Stats()
	if !last.Load() || state.Completed != 3 || state.Failed != 1 || state.Panicked != 1 {
		t.Fatal("失败/panic中断了队列", state)
	}
	if strings.Contains(logs.String(), "fixture-secret") || strings.Contains(logs.String(), "private-request-body") || !strings.Contains(logs.String(), "failure-trace") || !strings.Contains(logs.String(), "panic-trace") {
		t.Fatal("错误日志未关联请求或泄露详情")
	}
}

func TestIdleWorkersRetireAndRestartWithoutLosingQueuedWork(t *testing.T) {
	options := DefaultOptions()
	options.IdleTimeout = 10 * time.Millisecond
	executor := testExecutor(t, options)
	done := make(chan int, 100)
	for round := 0; round < 3; round++ {
		executor.Submit(context.Background(), func(context.Context) error { done <- 1; return nil })
		receive(t, done)
		deadline := time.Now().Add(time.Second)
		for executor.Stats().Workers != 0 {
			if time.Now().After(deadline) {
				t.Fatal("空闲执行单元没有退出")
			}
			time.Sleep(time.Millisecond)
		}
	}
	for i := 0; i < 100; i++ {
		for !executor.Submit(context.Background(), func(context.Context) error { done <- 1; return nil }) {
			time.Sleep(time.Millisecond)
		}
	}
	executor.Shutdown()
	wait(t, executor)
	if executor.Stats().Completed != 103 {
		t.Fatal("空闲退场/重启丢任务")
	}
}

func TestConcurrentSubmitShutdownAndStopAreBounded(t *testing.T) {
	executor := testExecutor(t, DefaultOptions())
	var accepted, completed atomic.Int64
	var group sync.WaitGroup
	for i := 0; i < 1000; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			if executor.Submit(requestContext(fmt.Sprintf("trace-%d", i)), func(context.Context) error { completed.Add(1); return nil }) {
				accepted.Add(1)
			}
		}(i)
	}
	group.Wait()
	for i := 0; i < 20; i++ {
		group.Add(1)
		go func() { defer group.Done(); executor.Shutdown() }()
	}
	group.Wait()
	wait(t, executor)
	if accepted.Load() != completed.Load() || int64(executor.Stats().Completed) != completed.Load() || executor.Stats().Workers != 0 {
		t.Fatal("并发提交/关闭丢失接受任务")
	}
}

func TestInvalidOptionsAndEmptyLifecycle(t *testing.T) {
	for _, options := range []Options{{}, {CoreWorkers: 2, MaxWorkers: 1, QueueCapacity: 1, IdleTimeout: time.Second}, {CoreWorkers: 1, MaxWorkers: 1, IdleTimeout: time.Second}, {CoreWorkers: 1, MaxWorkers: 1, QueueCapacity: 1}} {
		if _, err := New(options, slog.Default()); err == nil {
			t.Fatal("接受非法执行器配置")
		}
	}
	if _, err := New(DefaultOptions(), nil); err == nil {
		t.Fatal("接受空日志")
	}
	executor := testExecutor(t, DefaultOptions())
	if executor.Submit(nil, func(context.Context) error { return nil }) || executor.Submit(context.Background(), nil) {
		t.Fatal("接受非法任务")
	}
	if executor.Stats().Workers != 0 {
		t.Fatal("空执行器启动了永久goroutine")
	}
	executor.Shutdown()
	wait(t, executor)
	if executor.Wait(nil) == nil {
		t.Fatal("接受nil等待context")
	}
}
