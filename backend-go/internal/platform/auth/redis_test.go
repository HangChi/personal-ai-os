package auth

import (
	"testing"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func TestRedisSessionConstructor(t *testing.T) {
	client, err := redisclient.New(redisclient.Config{URI: "redis://localhost:6379"})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	for _, namespace := range []string{"", " ", "prefix\n", " prefix"} {
		if _, err := NewRedisStore(client, namespace, time.Minute); err == nil {
			t.Fatal("invalid namespace accepted")
		}
	}
	if _, err := NewRedisStore(nil, "fixture", time.Minute); err == nil {
		t.Fatal("nil client accepted")
	}
	if _, err := NewRedisStore(client, "fixture", 0); err == nil {
		t.Fatal("zero timeout accepted")
	}
	if _, err := NewRedisStore(client, "fixture", time.Nanosecond); err == nil {
		t.Fatal("submillisecond timeout accepted")
	}
	store, err := NewRedisStore(client, "fixture", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if store.key(sessionID(1)) != "fixture:go:v1:session:"+sessionID(1) {
		t.Fatal("Spring namespace isolation missing")
	}
}
