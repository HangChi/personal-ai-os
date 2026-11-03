package ratelimit

import (
	"context"
	"errors"
	"testing"
	"time"
)

type bucketCall struct {
	ctx      context.Context
	name     string
	capacity int
	period   time.Duration
}

type bucketStub struct {
	allowed bool
	err     error
	calls   []bucketCall
}

func (bucket *bucketStub) Consume(ctx context.Context, name string, capacity int, period time.Duration) (bool, error) {
	bucket.calls = append(bucket.calls, bucketCall{ctx, name, capacity, period})
	return bucket.allowed, bucket.err
}

type observation struct {
	backend Backend
	elapsed time.Duration
	failed  bool
}

type observerStub struct{ observations []observation }

func (observer *observerStub) RecordAcquisition(backend Backend, elapsed time.Duration, failed bool) {
	observer.observations = append(observer.observations, observation{backend, elapsed, failed})
}

func TestSharedBackendSelectionAndFailurePolicy(t *testing.T) {
	failure := errors.New("private backend endpoint and credentials")
	for _, test := range []struct {
		name        string
		redis       bool
		allowed     bool
		err         error
		wantAllowed bool
		wantError   bool
	}{
		{"postgres allows", false, true, nil, true, false},
		{"postgres rejects", false, false, nil, false, false},
		{"postgres failure propagates", false, false, failure, false, true},
		{"redis allows", true, true, nil, true, false},
		{"redis rejects", true, false, nil, false, false},
		{"redis failure denies without fallback", true, true, failure, false, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			postgres := &bucketStub{allowed: true}
			var redis *bucketStub
			var redisBackend Bucket
			selected := postgres
			backend := Postgres
			if test.redis {
				redis = &bucketStub{}
				redisBackend, selected, backend = redis, redis, Redis
			}
			selected.allowed, selected.err = test.allowed, test.err
			observer := &observerStub{}
			options := Options{true, 7, time.Minute}
			limiter, err := NewShared(options, postgres, redisBackend, observer)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			allowed, err := limiter.TryAcquire(ctx)
			if allowed != test.wantAllowed || (err != nil) != test.wantError {
				t.Fatalf("准入结果错误：allowed=%v err=%v", allowed, err)
			}
			if test.wantError && !errors.Is(err, failure) {
				t.Fatal("PostgreSQL 错误应保留原因")
			}
			if len(selected.calls) != 1 || selected.calls[0] != (bucketCall{ctx, "model-rate", 7, time.Minute}) {
				t.Fatalf("共享桶调用参数错误：%+v", selected.calls)
			}
			if test.redis && len(postgres.calls) != 0 {
				t.Fatal("Redis 拒绝/出错都不能回退数据库")
			}
			if len(observer.observations) != 1 {
				t.Fatal("每次共享准入应记录一次耗时")
			}
			got := observer.observations[0]
			if got.backend != backend || got.elapsed < 0 || got.failed != (test.redis && test.err != nil) {
				t.Fatalf("指标标签/错误计数策略错误：%+v", got)
			}
		})
	}
}

func TestSharedDisabledSkipsBothBackendsAndMetrics(t *testing.T) {
	postgres, redis, observer := &bucketStub{}, &bucketStub{}, &observerStub{}
	limiter, err := NewShared(Options{false, 1, time.Hour}, postgres, redis, observer)
	if err != nil {
		t.Fatal(err)
	}
	for range 100 {
		if allowed, err := limiter.TryAcquire(context.Background()); !allowed || err != nil {
			t.Fatalf("关闭限流应直接放行：%v %v", allowed, err)
		}
	}
	if len(postgres.calls)+len(redis.calls)+len(observer.observations) != 0 {
		t.Fatal("关闭限流不应接触后端或记录准入耗时")
	}
}

func TestSharedRequiresDatabaseAndValidOptions(t *testing.T) {
	if _, err := NewShared(DefaultOptions(), nil, &bucketStub{}, nil); err == nil {
		t.Fatal("共享限流缺少数据库后端不能静默退化成本地桶")
	}
	if _, err := NewShared(Options{}, &bucketStub{}, nil, nil); err == nil {
		t.Fatal("共享配置也必须校验")
	}
	limiter, err := NewShared(DefaultOptions(), &bucketStub{allowed: true}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if allowed, err := limiter.TryAcquire(context.Background()); !allowed || err != nil {
		t.Fatal("未装指标时应保持准入功能")
	}
}
