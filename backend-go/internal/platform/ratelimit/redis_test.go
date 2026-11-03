package ratelimit

import (
	"context"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func TestRedisBucketConfigurationAndJavaScript(t *testing.T) {
	client, err := redisclient.New(redisclient.Config{URI: "redis://localhost:6379"})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := NewRedisBucket(nil, "fixture"); err == nil {
		t.Fatal("nil client")
	}
	for _, namespace := range []string{"", " \t\u2003\u001f"} {
		if _, err := NewRedisBucket(client, namespace); err == nil {
			t.Fatal("blank namespace accepted")
		}
	}
	if _, err := NewRedisBucket(client, "\u00a0"); err != nil {
		t.Fatal("Java NBSP is not whitespace")
	}
	bucket, err := NewRedisBucket(client, "shared")
	if err != nil {
		t.Fatal(err)
	}
	if bucket.key != "personalos:rate:a4d26868017c0ccffe2efe50944ef4211834660cca834c6e9f86dec6a88246fa" {
		t.Fatal("Java namespace hash differs")
	}
	for _, row := range []struct {
		name     string
		capacity int
		period   time.Duration
	}{
		{"other", 1, time.Second}, {"model-rate", 0, time.Second}, {"model-rate", 1, 0},
	} {
		if allowed, err := bucket.Consume(context.Background(), row.name, row.capacity, row.period); allowed || err == nil {
			t.Fatal("invalid arguments accepted")
		}
	}
	data, err := os.ReadFile("../../../../backend/src/main/java/com/personalos/common/web/RedisRateBucket.java")
	if err != nil {
		t.Fatal(err)
	}
	_, java, ok := strings.Cut(string(data), `private static final String TAKE = """`)
	if !ok {
		t.Fatal("baseline Java TAKE missing")
	}
	java, _, ok = strings.Cut(java, `""";`)
	if !ok {
		t.Fatal("baseline Java text block incomplete")
	}
	if strings.Join(strings.Fields(java), " ") != strings.Join(strings.Fields(redisTakeLua), " ") {
		t.Fatal("Go Lua differs from original Java algorithm")
	}
}

func TestRedisBucketUses150MillisecondOperationLimit(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	stop, done := make(chan struct{}), make(chan struct{})
	defer func() { close(stop); listener.Close(); <-done }()
	go func() {
		defer close(done)
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		<-stop
	}()
	client, err := redisclient.New(redisclient.Config{URI: "redis://" + listener.Addr().String()})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	bucket, _ := NewRedisBucket(client, "fixture")
	start := time.Now()
	allowed, err := bucket.Consume(context.Background(), "model-rate", 1, time.Minute)
	if allowed || err == nil {
		t.Fatal("unresponsive Redis admitted request")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatal("150ms limit replaced by Redis default", elapsed)
	}
}
