//go:build integration

package confirmation

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需独立Flyway测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许独立测试库")
	}
	opts.Port = port
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := database.Open(ctx, connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	})
	return pool
}
func TestPostgresCASAcrossConnections(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	one, _ := NewPostgresStore(fixturePool(t))
	two, _ := NewPostgresStore(fixturePool(t))
	h := &fixtureHandler{actions: allActions}
	registry, _ := NewRegistry(h)
	first, _ := NewService(one, registry)
	second, _ := NewService(two, registry)
	dto, err := first.Create(ctx, DeleteTask, "跨连接删除", map[string]any{"id": "synthetic"}, 1, "fixture")
	if err != nil {
		t.Fatal(err)
	}
	var successful atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			service := first
			if i%2 == 1 {
				service = second
			}
			if result, err := service.Confirm(ctx, dto.ID, dto.Token); err == nil && result.Executed {
				successful.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if successful.Load() != 1 || h.calls.Load() != 1 {
		t.Fatalf("executed=%d handler=%d", successful.Load(), h.calls.Load())
	}
	stored, ok, err := two.Get(ctx, dto.ID)
	if err != nil || !ok || stored.Status != Confirmed || stored.ResolvedAt == nil {
		t.Fatalf("record=%+v found=%v err=%v", stored, ok, err)
	}
}

func TestPostgresPromptConversationSnapshot(t *testing.T) {
	ctx := context.Background()
	store, _ := NewPostgresStore(fixturePool(t))
	now := time.Date(2026, 11, 4, 0, 0, 0, 0, time.UTC)
	seeded := 0
	seed := func(conversation, summary string, status Status, created, expires time.Time) Record {
		t.Helper()
		seeded++
		r, err := store.Create(ctx, Record{
			Token: fmt.Sprintf("private-token-%d", seeded), ConversationID: conversation,
			Action: DeleteCalendarEvent, Summary: summary, Payload: `{"private":"private-payload"}`,
			CreatedAt: created, ExpiresAt: expires, Status: Pending,
		})
		if err != nil {
			t.Fatal(err)
		}
		if status != Pending {
			if ok, err := store.CompareAndSet(ctx, r.ID, Pending, status, now); err != nil || !ok {
				t.Fatalf("set %s: %v, %v", status, ok, err)
			}
		}
		return r
	}
	const conversation = "confirmation-prompt-fixture"
	for i := range 13 {
		seed(conversation, fmt.Sprintf("pending-%02d", i), Pending,
			now.Add(-time.Duration(i+1)*time.Second), now.Add(time.Minute))
	}
	seed(conversation, "same-instant-expiry", Pending, now, now)
	seed(conversation, "already-cancelled", Cancelled, now.Add(time.Second), now.Add(time.Minute))
	seed(conversation, strings.Repeat("字", 179)+"😀truncated", Confirmed, now.Add(2*time.Second), now.Add(time.Minute))
	seed("other-conversation", "isolated-summary", Pending, now, now.Add(time.Minute))

	check := func(at time.Time, count int) string {
		t.Helper()
		prompt, err := store.Prompt(ctx, conversation, at)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(prompt, fmt.Sprintf("尚待人工确认：%d 项", count)) {
			t.Fatalf("missing pending count: %s", prompt)
		}
		for _, forbidden := range []string{"private-token", "private-payload", "isolated-summary", "truncated", "😀"} {
			if strings.Contains(prompt, forbidden) {
				t.Fatalf("unexpected prompt text %q", forbidden)
			}
		}
		for _, expected := range []string{`"status":"CANCELLED"`, `"status":"CONFIRMED"`, `"status":"EXPIRED","summary":"same-instant-expiry"`} {
			if !strings.Contains(prompt, expected) {
				t.Fatalf("missing status record: %s", expected)
			}
		}
		return prompt
	}
	prompt := check(now, 13)
	lines := strings.Split(prompt, "\n")
	if len(lines) != 7 {
		t.Fatalf("unexpected prompt layout: %q", prompt)
	}
	for _, sample := range []struct{ index, size int }{{4, 10}, {6, 12}} {
		var records []promptRecord
		if err := json.Unmarshal([]byte(lines[sample.index]), &records); err != nil || len(records) != sample.size {
			t.Fatalf("line %d: got %d records, err=%v", sample.index, len(records), err)
		}
	}
	prompt = check(now.Add(time.Minute), 0)
	if strings.Contains(prompt, "当前待确认记录") {
		t.Fatal("expired records appeared in pending section")
	}
	for _, id := range []string{"", " \t"} {
		if got, err := store.Prompt(ctx, id, now); err != nil || got != "" {
			t.Fatalf("blank conversation returned %q, %v", got, err)
		}
	}
}
