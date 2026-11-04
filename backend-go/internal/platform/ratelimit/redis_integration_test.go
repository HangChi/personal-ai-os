//go:build integration

package ratelimit

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func redisRateFixture(t *testing.T) (*redisclient.Client, *RedisBucket) {
	t.Helper()
	if os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("仅运行专用临时 Redis fixture")
	}
	client, err := redisclient.New(redisclient.Config{URI: os.Getenv("GO_TEST_REDIS_URI"), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := client.Ping(context.Background()); err != nil {
		t.Fatal(err)
	}
	bucket, err := NewRedisBucket(client, "fixture:"+t.Name())
	if err != nil {
		t.Fatal(err)
	}
	return client, bucket
}

func TestRedisRateIndependentCallersAndConcurrentInitialization(t *testing.T) {
	_, first := redisRateFixture(t)
	_, second := redisRateFixture(t)
	for _, count := range []int{32, 128} {
		capacity := 5
		if count == 128 {
			capacity = 17
		}
		if _, err := first.client.Eval(context.Background(), `return redis.call('DEL',KEYS[1])`, []string{first.key}); err != nil {
			t.Fatal(err)
		}
		var group sync.WaitGroup
		var admitted atomic.Int32
		for i := 0; i < count; i++ {
			group.Add(1)
			go func(i int) {
				defer group.Done()
				bucket := first
				if i%2 == 0 {
					bucket = second
				}
				allowed, err := bucket.Consume(context.Background(), "model-rate", capacity, time.Hour)
				if err != nil {
					var network net.Error
					isNetwork := errors.As(err, &network)
					t.Errorf("%v (deadline=%t canceled=%t network=%t timeout=%t cause=%T)", err,
						errors.Is(err, context.DeadlineExceeded), errors.Is(err, context.Canceled),
						isNetwork, isNetwork && network.Timeout(), errors.Unwrap(err))
				}
				if allowed {
					admitted.Add(1)
				}
			}(i)
		}
		group.Wait()
		if int(admitted.Load()) != capacity {
			t.Fatalf("capacity=%d admitted=%d", capacity, admitted.Load())
		}
	}
}

func redisMillis(t *testing.T, client *redisclient.Client) int64 {
	t.Helper()
	value, err := client.Eval(context.Background(), `local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)`, nil)
	if err != nil {
		t.Fatal(err)
	}
	return value.(int64)
}

func TestRedisRateFractionFutureConfigurationAndTTL(t *testing.T) {
	client, bucket := redisRateFixture(t)
	ctx := context.Background()
	now := redisMillis(t, client)
	_, err := client.Eval(ctx, `return redis.call('HSET',KEYS[1],'tokens','0.25','at',ARGV[1],'capacity','7','period','60000')`, []string{bucket.key}, now-1000)
	if err != nil {
		t.Fatal(err)
	}
	if allowed, err := bucket.Consume(ctx, "model-rate", 7, time.Minute); allowed || err != nil {
		t.Fatal("fraction must reject", err)
	}
	value, err := client.Eval(ctx, `return redis.call('HMGET',KEYS[1],'tokens','at')`, []string{bucket.key})
	if err != nil {
		t.Fatal(err)
	}
	values := value.([]any)
	tokens, _ := strconv.ParseFloat(values[0].(string), 64)
	at, _ := strconv.ParseInt(values[1].(string), 10, 64)
	if tokens < 0.36 || tokens > 0.6 || at < now {
		t.Fatal("rejection did not persist fractional refill and server time")
	}
	future := redisMillis(t, client) + time.Hour.Milliseconds()
	if _, err := client.Eval(ctx, `return redis.call('HSET',KEYS[1],'tokens','0.5','at',ARGV[1])`, []string{bucket.key}, future); err != nil {
		t.Fatal(err)
	}
	if allowed, err := bucket.Consume(ctx, "model-rate", 7, time.Minute); allowed || err != nil {
		t.Fatal("future timestamp must not create tokens", err)
	}
	for _, options := range []Options{{Capacity: 8, Period: time.Minute}, {Capacity: 7, Period: 2 * time.Minute}} {
		if allowed, err := bucket.Consume(ctx, "model-rate", options.Capacity, options.Period); allowed || err == nil {
			t.Fatal("inconsistent config silently accepted")
		}
	}
	value, err = client.Eval(ctx, `return redis.call('HMGET',KEYS[1],'tokens','at','capacity','period')`, []string{bucket.key})
	if err != nil {
		t.Fatal(err)
	}
	values = value.([]any)
	if values[0] != "0.5" || values[1] != strconv.FormatInt(future, 10) || values[2] != "7" || values[3] != "60000" {
		t.Fatal("future/config error changed state")
	}
	value, err = client.Eval(ctx, `return redis.call('PTTL',KEYS[1])`, []string{bucket.key})
	if err != nil || value.(int64) < 59000 || value.(int64) > 61000 {
		t.Fatal("period+1 second expiry missing", err)
	}
	if _, err := client.Eval(ctx, `return redis.call('DEL',KEYS[1])`, []string{bucket.key}); err != nil {
		t.Fatal(err)
	}
	if allowed, err := bucket.Consume(ctx, "model-rate", 1, time.Nanosecond); !allowed || err != nil {
		t.Fatal("sub-millisecond period not clamped to 1ms", err)
	}
	value, err = client.Eval(ctx, `return redis.call('HGET',KEYS[1],'period')`, []string{bucket.key})
	if err != nil || value != "1" {
		t.Fatal("millisecond serialization", err)
	}
}

func TestRedisRateFailureDoesNotFallbackAndCanceledDoesNotSpend(t *testing.T) {
	client, bucket := redisRateFixture(t)
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if allowed, err := bucket.Consume(canceled, "model-rate", 1, time.Hour); allowed || !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation", err)
	}
	if allowed, err := bucket.Consume(context.Background(), "model-rate", 1, time.Hour); !allowed || err != nil {
		t.Fatal("canceled operation spent a token", err)
	}
	postgres := &bucketStub{allowed: true}
	observer := &observerStub{}
	limiter, err := NewShared(Options{Enabled: true, Capacity: 1, Period: time.Hour}, postgres, bucket, observer)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	if allowed, err := limiter.TryAcquire(context.Background()); allowed || err != nil {
		t.Fatal("Redis failure must deny without propagating", err)
	}
	if len(postgres.calls) != 0 || len(observer.observations) != 1 || !observer.observations[0].failed || observer.observations[0].backend != Redis {
		t.Fatal("fallback/metric contract")
	}
}

func TestRedisRateTwoProcessesShareBudget(t *testing.T) {
	_, bucket := redisRateFixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var children []*exec.Cmd
	var outputs []*bytes.Buffer
	for i := 0; i < 2; i++ {
		child := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestRedisRateChild$")
		child.Env = append(os.Environ(), "GO_REDIS_RATE_CHILD=consume", "GO_REDIS_RATE_NAMESPACE=fixture:"+t.Name())
		output := &bytes.Buffer{}
		child.Stdout = output
		child.Stderr = output
		if err := child.Start(); err != nil {
			for _, started := range children {
				started.Process.Kill()
				started.Wait()
			}
			t.Fatal(err)
		}
		children = append(children, child)
		outputs = append(outputs, output)
		t.Cleanup(func() {
			if child.ProcessState == nil {
				child.Process.Kill()
				child.Wait()
			}
		})
	}
	total := 0
	for i, child := range children {
		err := child.Wait()
		if err != nil {
			t.Errorf("独立 Redis 消费进程失败: %v\n%s", err, outputs[i])
			continue
		}
		found := false
		for _, line := range strings.Split(outputs[i].String(), "\n") {
			if value, ok := strings.CutPrefix(line, "REDIS_ADMITTED="); ok {
				n, err := strconv.Atoi(value)
				if err != nil {
					t.Fatal(err)
				}
				total += n
				found = true
			}
		}
		if !found {
			t.Error("independent process result missing")
		}
	}
	if total != 13 {
		t.Fatalf("two processes admitted=%d, want 13", total)
	}
	if allowed, err := bucket.Consume(context.Background(), "model-rate", 13, time.Hour); allowed || err != nil {
		t.Fatal("parent has separate budget", err)
	}
}

func TestRedisRateChild(t *testing.T) {
	if os.Getenv("GO_REDIS_RATE_CHILD") != "consume" {
		t.Skip("仅供独立消费进程")
	}
	client, _ := redisRateFixture(t)
	bucket, err := NewRedisBucket(client, os.Getenv("GO_REDIS_RATE_NAMESPACE"))
	if err != nil {
		t.Fatal(err)
	}
	admitted := 0
	for i := 0; i < 32; i++ {
		if allowed, err := bucket.Consume(context.Background(), "model-rate", 13, time.Hour); err != nil {
			t.Fatal(err)
		} else if allowed {
			admitted++
		}
	}
	fmt.Printf("REDIS_ADMITTED=%d\n", admitted)
}
