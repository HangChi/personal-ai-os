package ratelimit

import (
	"context"
	"errors"
	"time"
)

type Backend string

const (
	Postgres Backend = "postgres"
	Redis    Backend = "redis"
)

// Bucket 是共享存储的原子消费边界；PostgreSQL 已实现，Redis 适配器仍待迁移。
// 实现必须自行保证跨进程原子性，并使用共享后端时钟，不得调用本地桶替代。
type Bucket interface {
	Consume(ctx context.Context, name string, capacity int, period time.Duration) (bool, error)
}

// Observer 对应 Java personalos.rate.limit.acquire 及 errors 指标。
// failed 只在 Redis 返回错误时为 true；不传入原始错误或请求以控制隐私与标签基数。
// 实现必须支持并发调用；耗时在拒绝和数据库错误时也记录。
type Observer interface {
	RecordAcquisition(backend Backend, elapsed time.Duration, failed bool)
}

// SharedLimiter 只实现 Java 已装配共享后端后的选择/故障策略。
// 它不创建连接、不提供本地回退，也尚未接入应用诊断入口。
type SharedLimiter struct {
	options  Options
	bucket   Bucket
	backend  Backend
	observer Observer
}

func NewShared(options Options, postgres, redis Bucket, observer Observer) (*SharedLimiter, error) {
	if err := options.Validate(); err != nil {
		return nil, err
	}
	if postgres == nil {
		return nil, errors.New("共享模型限流必须显式提供 PostgreSQL 协调后端")
	}
	selected, backend := postgres, Postgres
	if redis != nil {
		selected, backend = redis, Redis
	}
	return &SharedLimiter{options: options, bucket: selected, backend: backend, observer: observer}, nil
}

func (limiter *SharedLimiter) TryAcquire(ctx context.Context) (allowed bool, err error) {
	if !limiter.options.Enabled {
		return true, nil
	}
	start := time.Now()
	failed := false
	defer func() {
		if limiter.observer != nil {
			limiter.observer.RecordAcquisition(limiter.backend, time.Since(start), failed)
		}
	}()
	allowed, err = limiter.bucket.Consume(ctx, "model-rate", limiter.options.Capacity, limiter.options.Period)
	if err != nil {
		if limiter.backend == Redis {
			// 与 Java 一致：Redis 异常计错并拒绝，不重试、不回退 PostgreSQL/本地。
			failed = true
			return false, nil
		}
		return false, err
	}
	return allowed, nil
}
