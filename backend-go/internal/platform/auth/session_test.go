package auth

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

func sessionID(index int) string { return fmt.Sprintf("%064x", index) }

func TestMemorySessionExpiryRotationAndNoResurrection(t *testing.T) {
	ctx := context.Background()
	now := time.Now()
	store, err := newMemoryStore(time.Minute, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	first, second := sessionID(1), sessionID(2)
	if err := store.Rotate(ctx, "", first, Session{Username: "admin"}); err != nil {
		t.Fatal(err)
	}
	now = now.Add(59 * time.Second)
	if state, ok, err := store.Load(ctx, first); !ok || err != nil || state.Username != "admin" {
		t.Fatal("session not readable")
	}
	now = now.Add(59 * time.Second)
	if _, ok, _ := store.Load(ctx, first); !ok {
		t.Fatal("activity did not extend expiry")
	}
	state, ok, err := store.EnsureCSRF(ctx, first, "first-token")
	if err != nil || !ok || state.CSRFToken != "first-token" {
		t.Fatal("token not assigned")
	}
	state, _, _ = store.EnsureCSRF(ctx, first, "replacement-token")
	if state.CSRFToken != "first-token" {
		t.Fatal("existing token overwritten")
	}
	if err := store.Rotate(ctx, first, second, Session{Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := store.Load(ctx, first); ok {
		t.Fatal("old ID survived rotation")
	}
	if state, ok, _ := store.Load(ctx, second); !ok || state.Username != "owner" || state.CSRFToken != "" {
		t.Fatal("rotation carried old token")
	}
	if _, ok, _ := store.EnsureCSRF(ctx, first, "stale"); ok {
		t.Fatal("stale token update resurrected old session")
	}
	if err := store.Delete(ctx, second); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := store.EnsureCSRF(ctx, second, "stale"); ok {
		t.Fatal("logged-out session resurrected")
	}
	if err := store.Delete(ctx, second); err != nil {
		t.Fatal("delete not idempotent")
	}
	if err := store.Rotate(ctx, "", first, Session{}); err != nil {
		t.Fatal(err)
	}
	now = now.Add(time.Minute)
	if _, ok, _ := store.Load(ctx, first); ok {
		t.Fatal("exact expiry must be expired")
	}
	if _, ok, _ := store.EnsureCSRF(ctx, first, "stale"); ok {
		t.Fatal("expired session resurrected")
	}
}

func TestMemorySessionConcurrentTokenAndCollision(t *testing.T) {
	ctx := context.Background()
	store, _ := NewMemoryStore(time.Minute)
	id, other := sessionID(1), sessionID(2)
	if err := store.Rotate(ctx, "", id, Session{Username: "admin"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Rotate(ctx, "", other, Session{Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Rotate(ctx, id, other, Session{}); !errors.Is(err, ErrSessionCollision) {
		t.Fatalf("collision: %v", err)
	}
	if state, ok, _ := store.Load(ctx, id); !ok || state.Username != "admin" {
		t.Fatal("collision destroyed old session")
	}
	if err := store.Rotate(ctx, id, id, Session{}); err == nil {
		t.Fatal("same ID rotation accepted")
	}
	var group sync.WaitGroup
	tokens := make(chan string, 100)
	for i := 0; i < 100; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			state, ok, err := store.EnsureCSRF(ctx, id, fmt.Sprint(i))
			if !ok || err != nil {
				t.Error("token update", err)
				return
			}
			tokens <- state.CSRFToken
		}(i)
	}
	group.Wait()
	close(tokens)
	first := ""
	for token := range tokens {
		if first == "" {
			first = token
		}
		if token != first {
			t.Fatal("concurrent token responses differ")
		}
	}
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if _, _, err := store.Load(canceled, id); !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation ignored")
	}
	if err := store.Delete(canceled, id); !errors.Is(err, context.Canceled) {
		t.Fatal("canceled delete applied")
	}
	for _, bad := range []string{"", "short", strings.Repeat("g", 64), strings.Repeat("a", 65)} {
		if err := store.Rotate(ctx, "", bad, Session{}); err == nil {
			t.Fatal("invalid ID accepted")
		}
	}
	if _, err := NewMemoryStore(0); err == nil {
		t.Fatal("zero timeout accepted")
	}
}
