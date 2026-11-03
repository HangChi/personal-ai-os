package ratelimit

import (
	"math"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestDefaultsAndInvalidOptions(t *testing.T) {
	defaults := DefaultOptions()
	if !defaults.Enabled || defaults.Capacity != 60 || defaults.Period != time.Minute {
		t.Fatalf("Java 默认值不一致：%+v", defaults)
	}
	for _, test := range []struct {
		name     string
		options  Options
		property string
	}{
		{"zero capacity", Options{true, 0, time.Minute}, "capacity"},
		{"negative capacity", Options{true, -1, time.Minute}, "capacity"},
		{"zero period", Options{true, 1, 0}, "period"},
		{"negative period", Options{true, 1, -time.Second}, "period"},
		{"disabled still validates", Options{false, 0, time.Minute}, "capacity"},
	} {
		t.Run(test.name, func(t *testing.T) {
			called := false
			_, err := newLocal(test.options, func() time.Time { called = true; return time.Time{} })
			if err == nil || !strings.Contains(err.Error(), test.property) || called {
				t.Fatalf("非法配置必须在读时钟前失败：%v, called=%v", err, called)
			}
		})
	}
	if strconv.IntSize == 64 {
		tooLarge := int64(math.MaxInt32) + 1
		if _, err := NewLocal(Options{true, int(tooLarge), time.Minute}); err == nil {
			t.Fatal("不能超出 Java int 的配置范围")
		}
	}
}

func fixedBucket(t *testing.T, options Options) (*LocalBucket, *time.Time) {
	t.Helper()
	now := time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC)
	bucket, err := newLocal(options, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	return bucket, &now
}

func TestInitiallyFullThenExhausted(t *testing.T) {
	bucket, _ := fixedBucket(t, Options{true, 3, time.Hour})
	for index := 0; index < 3; index++ {
		if !bucket.TryAcquire() {
			t.Fatalf("初始第 %d 个令牌应可用", index+1)
		}
	}
	if bucket.TryAcquire() || bucket.availableTokens() != 0 {
		t.Fatal("第 4 次请求应被拒绝")
	}
}

func TestSmoothFractionalRefillAndCapacity(t *testing.T) {
	bucket, now := fixedBucket(t, Options{true, 7, time.Minute})
	for range 7 {
		bucket.TryAcquire()
	}
	*now = now.Add(4 * time.Second)
	if bucket.TryAcquire() {
		t.Fatal("不足一个令牌不能放行")
	}
	if math.Abs(bucket.availableTokens()-7.0/15) > 1e-12 {
		t.Fatal("分数令牌不能被取整或丢弃")
	}
	*now = now.Add(5 * time.Second)
	if !bucket.TryAcquire() || bucket.TryAcquire() {
		t.Fatal("9 秒应只补充 1.05 个令牌")
	}
	*now = now.Add(24 * time.Hour)
	if bucket.availableTokens() != 7 {
		t.Fatal("长时间空闲不能超过容量上限")
	}
	for range 7 {
		if !bucket.TryAcquire() {
			t.Fatal("完整周期后应补满")
		}
	}
	if bucket.TryAcquire() {
		t.Fatal("不能累积超过容量的突发额度")
	}
}

func TestZeroElapsedAndClockRollback(t *testing.T) {
	bucket, now := fixedBucket(t, Options{true, 1, time.Second})
	bucket.TryAcquire()
	original := *now
	*now = now.Add(-time.Second)
	if bucket.TryAcquire() || bucket.availableTokens() != 0 {
		t.Fatal("回拨不应增加额度")
	}
	*now = original
	if bucket.TryAcquire() {
		t.Fatal("回拨不能提前重置上次补充时间")
	}
	*now = now.Add(time.Second)
	if !bucket.TryAcquire() {
		t.Fatal("恢复到完整周期后应补充")
	}
}

func TestDisabledDoesNotConsumeOrRefill(t *testing.T) {
	bucket, _ := fixedBucket(t, Options{false, 1, time.Hour})
	bucket.now = func() time.Time { t.Fatal("关闭时不应进入补充逻辑"); return time.Time{} }
	for range 1000 {
		if !bucket.TryAcquire() {
			t.Fatal("关闭限流应直接放行")
		}
	}
}

func TestConcurrentAcquisitionsDoNotOverspend(t *testing.T) {
	const capacity = 37
	bucket, _ := fixedBucket(t, Options{true, capacity, time.Hour})
	var accepted atomic.Int32
	var group sync.WaitGroup
	for range 1000 {
		group.Go(func() {
			if bucket.TryAcquire() {
				accepted.Add(1)
			}
		})
	}
	group.Wait()
	if accepted.Load() != capacity || bucket.availableTokens() != 0 {
		t.Fatalf("并发透支或丢失令牌：%d", accepted.Load())
	}
}
