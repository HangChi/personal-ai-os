// Package ratelimit 移植 Java 模型请求限流；本地桶只用于独立构造和测试。
package ratelimit

import (
	"fmt"
	"math"
	"sync"
	"time"
)

// Options 对应 RateLimitProperties。即使关闭限流，容量和周期也必须合法。
// 零值不表示默认配置；调用 DefaultOptions 获取 Java 默认值。
type Options struct {
	Enabled  bool
	Capacity int
	Period   time.Duration
}

func DefaultOptions() Options {
	return Options{Enabled: true, Capacity: 60, Period: time.Minute}
}

func (options Options) Validate() error {
	// Java capacity 是有符号 32 位 int，不能让 Go 的平台字长扩大配置范围。
	if options.Capacity <= 0 {
		return fmt.Errorf("app.rate-limit.capacity 必须为正数，当前为 %d", options.Capacity)
	}
	if int64(options.Capacity) > math.MaxInt32 {
		return fmt.Errorf("app.rate-limit.capacity 必须为正的 32 位整数，当前为 %d", options.Capacity)
	}
	if options.Period <= 0 {
		return fmt.Errorf("app.rate-limit.period 必须为正的时长，当前为 %s", options.Period)
	}
	return nil
}

// LocalBucket 是进程内、线程安全、平滑补充的单桶。不用于多实例全局限流。
// 构造后立即注满；不存在持久化或共享后端失败后的本地回退。
type LocalBucket struct {
	options       Options
	nanosPerToken float64
	mu            sync.Mutex
	tokens        float64
	lastRefill    time.Time
	now           func() time.Time
}

func NewLocal(options Options) (*LocalBucket, error) {
	return newLocal(options, time.Now)
}

func newLocal(options Options, now func() time.Time) (*LocalBucket, error) {
	if err := options.Validate(); err != nil {
		return nil, err
	}
	return &LocalBucket{
		options: options, nanosPerToken: float64(options.Period) / float64(options.Capacity),
		tokens: float64(options.Capacity), lastRefill: now(), now: now,
	}, nil
}

func (bucket *LocalBucket) TryAcquire() bool {
	if !bucket.options.Enabled {
		return true
	}
	bucket.mu.Lock()
	defer bucket.mu.Unlock()
	bucket.refill()
	if bucket.tokens < 1 {
		return false
	}
	bucket.tokens--
	return true
}

// availableTokens 仅用于包内自检。调用时同样补充，不参与准入决策。
func (bucket *LocalBucket) availableTokens() float64 {
	bucket.mu.Lock()
	defer bucket.mu.Unlock()
	bucket.refill()
	return bucket.tokens
}

func (bucket *LocalBucket) refill() {
	now := bucket.now()
	// time.Now 带单调时钟；测试仍覆盖 Java 的零间隔/回拨保护。
	elapsed := now.Sub(bucket.lastRefill)
	if elapsed <= 0 {
		return
	}
	bucket.tokens = math.Min(float64(bucket.options.Capacity), bucket.tokens+float64(elapsed)/bucket.nanosPerToken)
	bucket.lastRefill = now
}
