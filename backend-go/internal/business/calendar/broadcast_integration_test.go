//go:build integration

package calendar

import (
	"context"
	"database/sql"
	amqp "github.com/rabbitmq/amqp091-go"
	"io"
	"log/slog"
	"os"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/testdb"
	"sync/atomic"
	"testing"
	"time"
)

func TestCalendarBroadcastRealDeliveryDeduplicationAndRouting(t *testing.T) {
	address := os.Getenv("GO_TEST_KNOWLEDGE_RABBIT_URI")
	if address == "" {
		t.Skip("requires disposable RabbitMQ fixture")
	}
	pool := testdb.Open(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	exec := func(q string) {
		t.Helper()
		if e := pool.WithConn(ctx, func(c *sql.Conn) error { _, e := c.ExecContext(ctx, q); return e }); e != nil {
			t.Fatal(e)
		}
	}
	exec("TRUNCATE reminder_broadcast_state,reminder CASCADE")
	locks, e := coordination.New(pool)
	if e != nil {
		t.Fatal(e)
	}
	defer locks.Close(context.Background())
	events := NewEvents(pool, config.CalendarProperties{}, time.UTC)
	stream := NewStream(ctx, events, time.Hour)
	defer stream.Close()
	subscriber, e := StartBroadcast(ctx, address, events, stream, locks, false, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer subscriber.Close()
	var sent atomic.Uint64
	e = withRabbit(ctx, address, func(ctx context.Context, ch *amqp.Channel) error {
		if e := ch.Confirm(false); e != nil {
			return e
		}
		returns := ch.NotifyReturn(make(chan amqp.Return, 1))
		// Wait for a subscriber queue to bind by probing a confirmed mandatory message.
		for {
			receipt, e := ch.PublishWithDeferredConfirmWithContext(ctx, broadcastExchange, "", true, false, amqp.Publishing{Body: []byte("probe")})
			if e != nil {
				return e
			}
			if _, e = receipt.WaitContext(ctx); e != nil {
				return e
			}
			_, received, _ := subscriber.Counts()
			if received > 0 {
				break
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(20 * time.Millisecond):
			}
			select {
			case <-returns:
			default:
			}
		}
		// Drain any unroutable probe notification before testing application publication.
		for {
			select {
			case <-returns:
			default:
				goto ready
			}
		}
	ready:
		if e := events.publishChange(ctx, ch, returns, locks, &sent); e != nil {
			return e
		}
		if sent.Load() != 1 {
			t.Errorf("expected one changed snapshot, got %d", sent.Load())
		}
		if e := events.publishChange(ctx, ch, returns, locks, &sent); e != nil {
			return e
		}
		if sent.Load() != 1 {
			t.Error("unchanged digest was republished")
		}
		return nil
	})
	if e != nil {
		t.Fatal(e)
	}
	subscriber.Close()
	exec("DELETE FROM reminder_broadcast_state")
	// With no subscribers, mandatory publication is not considered durable delivery.
	e = withRabbit(ctx, address, func(ctx context.Context, ch *amqp.Channel) error {
		if e := ch.Confirm(false); e != nil {
			return e
		}
		returns := ch.NotifyReturn(make(chan amqp.Return, 1))
		e := events.publishChange(ctx, ch, returns, locks, &sent)
		if e == nil {
			t.Error("unroutable broadcast accepted")
		}
		return nil
	})
	if e != nil {
		t.Fatal(e)
	}
	if sent.Load() != 1 {
		t.Fatal("unroutable snapshot checkpointed")
	}
	// Exercise the managed publisher loop and orderly shutdown.
	both, e := StartBroadcast(ctx, address, events, stream, locks, true, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	for {
		s, r, _ := both.Counts()
		if s > 0 && r > 0 {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("broadcast loop never delivered")
		case <-time.After(20 * time.Millisecond):
		}
	}
	both.Close()
}
