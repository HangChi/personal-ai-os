// Package modelgate 对应 Java ModelConcurrencyGate；请求与后台执行共享租约容量。
package modelgate

import (
	"context"
	"errors"
	"math"
	"sync/atomic"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

const DefaultLimit = 4

type Gate struct {
	limit       int64
	inUse       atomic.Int64
	coordinator *coordination.Coordinator
	background  bool
}

// NewLocal 只用于独立构造/测试；正式多实例保护必须 NewShared。
func NewLocal(limit int) (*Gate, error) {
	if limit < 1 || int64(limit) > math.MaxInt32 {
		return nil, errors.New("模型并发上限必须为正的 32 位整数")
	}
	return &Gate{limit: int64(limit)}, nil
}

func NewShared(limit int, role config.Role, coordinator *coordination.Coordinator) (*Gate, error) {
	gate, err := NewLocal(limit)
	if err != nil {
		return nil, err
	}
	if coordinator == nil {
		return nil, errors.New("共享模型并发必须显式提供数据库协调器")
	}
	if _, err := config.ParseRole(string(role)); err != nil {
		return nil, err
	}
	gate.coordinator, gate.background = coordinator, role.Worker()
	return gate, nil
}

// TryAcquire 非阻塞本地许可；共享获取仍遵守调用方 context/数据库池期限。
// Worker 先领 model-background(max(1,limit-1))，再领 model-concurrency(limit)。
func (gate *Gate) TryAcquire(ctx context.Context) (lease *Lease, err error) {
	if ctx == nil {
		return nil, errors.New("模型并发 context 不能为空")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	for {
		used := gate.inUse.Load()
		if used >= gate.limit {
			return nil, nil
		}
		if gate.inUse.CompareAndSwap(used, used+1) {
			break
		}
	}
	var background *coordination.Lease
	success := false
	defer func() {
		if !success {
			defer gate.inUse.Add(-1)
			if background != nil {
				cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				err = errors.Join(err, background.Release(cleanup))
			}
		}
	}()
	var shared *coordination.Lease
	if gate.coordinator != nil {
		if gate.background {
			background, err = gate.coordinator.Acquire(ctx, "model-background", max(1, int(gate.limit)-1))
			if err != nil || background == nil {
				return nil, err
			}
		}
		shared, err = gate.coordinator.Acquire(ctx, "model-concurrency", int(gate.limit))
		if err != nil || shared == nil {
			return nil, err
		}
	}
	success = true
	return &Lease{gate: gate, shared: shared, background: background}, nil
}

type Lease struct {
	gate               *Gate
	shared, background *coordination.Lease
	closed             atomic.Bool
}

// Release 所有路径归还本地许可；共享/后台释放失败也继续清理另一份租约。
func (lease *Lease) Release(ctx context.Context) (err error) {
	if ctx == nil {
		return errors.New("模型并发释放 context 不能为空")
	}
	if !lease.closed.CompareAndSwap(false, true) {
		return nil
	}
	defer lease.gate.inUse.Add(-1)
	if lease.shared != nil {
		err = lease.shared.Release(ctx)
	}
	if lease.background != nil {
		err = errors.Join(err, lease.background.Release(ctx))
	}
	return err
}
