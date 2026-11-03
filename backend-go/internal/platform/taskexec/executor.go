// Package taskexec 提供轮次结束后尽力而为的有界任务执行器，不代替持久化任务队列。
package taskexec

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

type Options struct {
	CoreWorkers   int
	MaxWorkers    int
	QueueCapacity int
	IdleTimeout   time.Duration
}

func DefaultOptions() Options {
	return Options{CoreWorkers: 1, MaxWorkers: 2, QueueCapacity: 32, IdleTimeout: time.Minute}
}

type Task func(context.Context) error

type work struct {
	ctx  context.Context
	task Task
}

// Snapshot 不包含任务内容、请求身份或凭据；字段在同一互斥锁下取得一致快照。
type Snapshot struct {
	Workers, Active, Queued                  int
	Accepted, Completed, Rejected, Discarded uint64
	Failed, Panicked                         uint64
	Shutdown, Stopped                        bool
}

type Executor struct {
	options Options
	logger  *slog.Logger
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	queue   []work
	state   Snapshot
	changed chan struct{}
	done    chan struct{}
	closed  bool
}

func New(options Options, logger *slog.Logger) (*Executor, error) {
	if options.CoreWorkers <= 0 || options.MaxWorkers < options.CoreWorkers || options.QueueCapacity <= 0 || options.IdleTimeout <= 0 {
		return nil, errors.New("后台执行器必须具有正容量/空闲期限且最大并发不低于核心并发")
	}
	if logger == nil {
		return nil, errors.New("后台执行器日志不能为空")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Executor{options: options, logger: logger, ctx: ctx, cancel: cancel,
		changed: make(chan struct{}), done: make(chan struct{})}, nil
}

// Submit 保留 Java 顺序：先核心执行单元、再队列、队列满后扩到最大并发，
// 全满/已关闭时丢弃新任务并返回 false。不等待容量，不自动重试。
// 仅捕获提交时的请求 ID，任务取消来自执行器 Stop；不继承 HTTP 身份或 deadline。
func (executor *Executor) Submit(source context.Context, task Task) bool {
	executor.mu.Lock()
	defer executor.mu.Unlock()
	if source == nil || task == nil || executor.state.Shutdown {
		executor.state.Rejected++
		return false
	}
	item := work{ctx: httpmiddleware.CopyRequestID(executor.ctx, source), task: task}
	switch {
	case executor.state.Workers < executor.options.CoreWorkers:
		executor.startLocked(item)
	case len(executor.queue) < executor.options.QueueCapacity:
		executor.queue = append(executor.queue, item)
		executor.signalLocked()
	case executor.state.Workers < executor.options.MaxWorkers:
		executor.startLocked(item)
	default:
		executor.state.Rejected++
		return false
	}
	executor.state.Accepted++
	return true
}

func (executor *Executor) startLocked(item work) {
	executor.state.Workers++
	go executor.worker(item)
}

func (executor *Executor) signalLocked() {
	close(executor.changed)
	executor.changed = make(chan struct{})
}

func (executor *Executor) popLocked() work {
	item := executor.queue[0]
	executor.queue[0] = work{}
	executor.queue = executor.queue[1:]
	return item
}

func (executor *Executor) worker(initial work) {
	defer executor.workerExited()
	item := &initial
	for {
		if item != nil {
			executor.mu.Lock()
			if executor.state.Stopped {
				executor.state.Discarded++
				executor.mu.Unlock()
				return
			}
			executor.state.Active++
			executor.mu.Unlock()
			executor.execute(*item)
			executor.mu.Lock()
			executor.state.Active--
			executor.state.Completed++
			executor.mu.Unlock()
			// 尽快释放闭包/context；不在空闲期间保留上一任务。
			*item = work{}
			item = nil
		}
		executor.mu.Lock()
		if executor.state.Stopped {
			executor.mu.Unlock()
			return
		}
		if len(executor.queue) > 0 {
			next := executor.popLocked()
			item = &next
			executor.mu.Unlock()
			continue
		}
		if executor.state.Shutdown {
			executor.mu.Unlock()
			return
		}
		changed := executor.changed
		executor.mu.Unlock()
		timer := time.NewTimer(executor.options.IdleTimeout)
		select {
		case <-changed:
			timer.Stop()
		case <-timer.C:
			return
		}
	}
}

func (executor *Executor) execute(item work) {
	defer func() {
		if recover() != nil {
			executor.mu.Lock()
			executor.state.Panicked++
			executor.mu.Unlock()
			// 不打印 panic 值/堆栈，其中可能包含请求或模型凭据。
			executor.logger.Error("后台任务发生异常", "requestId", httpmiddleware.RequestID(item.ctx))
		}
	}()
	if err := item.task(item.ctx); err != nil {
		executor.mu.Lock()
		executor.state.Failed++
		executor.mu.Unlock()
		executor.logger.Warn("后台任务执行失败", "requestId", httpmiddleware.RequestID(item.ctx))
	}
}

func (executor *Executor) workerExited() {
	executor.mu.Lock()
	defer executor.mu.Unlock()
	executor.state.Workers--
	// 空闲退场与 Submit 竞争时，最后一个 worker 不能留下无人处理的已接受队列。
	if !executor.state.Stopped && executor.state.Workers == 0 && len(executor.queue) > 0 {
		executor.startLocked(executor.popLocked())
	}
	executor.finishLocked()
}

func (executor *Executor) finishLocked() {
	if executor.state.Shutdown && executor.state.Workers == 0 && !executor.closed {
		executor.closed = true
		executor.cancel()
		close(executor.done)
	}
}

// Shutdown 幂等停止接收并排空已接受任务；不取消运行任务。Wait 提供调用方等待期限。
func (executor *Executor) Shutdown() {
	executor.mu.Lock()
	defer executor.mu.Unlock()
	if !executor.state.Shutdown {
		executor.state.Shutdown = true
		executor.signalLocked()
	}
	executor.finishLocked()
}

// Stop 幂等取消运行任务并丢弃排队任务，返回本次清空的队列数。
// Go 无法安全强杀不遵守 context 的任务，Wait 不能把未退出的任务报告为完成。
func (executor *Executor) Stop() int {
	executor.mu.Lock()
	defer executor.mu.Unlock()
	if executor.state.Stopped {
		return 0
	}
	executor.state.Shutdown, executor.state.Stopped = true, true
	dropped := len(executor.queue)
	executor.state.Discarded += uint64(dropped)
	executor.queue = nil
	executor.cancel()
	executor.signalLocked()
	executor.finishLocked()
	return dropped
}

func (executor *Executor) Wait(ctx context.Context) error {
	if ctx == nil {
		return errors.New("后台执行器等待 context 不能为空")
	}
	select {
	case <-executor.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (executor *Executor) Stats() Snapshot {
	executor.mu.Lock()
	defer executor.mu.Unlock()
	state := executor.state
	state.Queued = len(executor.queue)
	return state
}
