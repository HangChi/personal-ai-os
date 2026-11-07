package calendar

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

func TestBroadcastValidatesConfigurationAndClosesInactiveRoles(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, address := range []string{"http://127.0.0.1", "amqp://bad host", "%"} {
		if b, err := StartBroadcast(context.Background(), address, nil, nil, nil, false, logger); err == nil || b != nil {
			t.Fatal("invalid URI accepted", address, err)
		}
	}
	b, err := StartBroadcast(context.Background(), "amqp://127.0.0.1:1", nil, nil, nil, false, logger)
	if err != nil {
		t.Fatal(err)
	}
	b.Close()
	b.Close()
	if sent, received, retry := b.Counts(); sent != 0 || received != 0 || retry != 0 {
		t.Fatal(sent, received, retry)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	b, err = StartBroadcast(ctx, "amqp://127.0.0.1:1", nil, &Stream{wakeup: make(chan struct{}, 1)}, nil, true, logger)
	if err != nil {
		t.Fatal(err)
	}
	b.Close()
}

func TestBroadcastCancelsIncompleteAMQPHandshake(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	accepted := make(chan struct{})
	serverDone := make(chan struct{})
	go func() {
		defer close(serverDone)
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		var header [8]byte
		if _, err = io.ReadFull(conn, header[:]); err != nil {
			return
		}
		close(accepted)
		_, _ = io.Copy(io.Discard, conn)
	}()
	done := make(chan error, 1)
	go func() {
		done <- withRabbit(ctx, "amqp://"+listener.Addr().String(), func(context.Context, *amqp.Channel) error {
			t.Error("incomplete handshake invoked broadcast")
			return nil
		})
	}()
	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("AMQP handshake not attempted")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled handshake succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("broadcast ignored cancellation")
	}
	select {
	case <-serverDone:
	case <-time.After(time.Second):
		t.Fatal("broadcast socket leaked")
	}
}

func TestBroadcastPublicationStopsBeforeAMQPOnBusyReadFailureOrUnchangedDigest(t *testing.T) {
	failure := errors.New("synthetic broadcast storage failure")
	for _, mode := range []string{"acquire-failed", "occupied", "reminders-failed", "digest-failed", "unchanged"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), execStep("INSERT INTO runtime_lease"), queryStep("WITH ready AS", sqlValues(0)), terminalStep("commit")}
			if mode == "acquire-failed" {
				steps = []sqlStep{{op: "begin", err: failure}}
			}
			if mode == "occupied" {
				steps[2].rows = nil
			}
			if mode != "acquire-failed" && mode != "occupied" {
				read := queryStep("FROM reminder", reminderRow())
				if mode == "reminders-failed" {
					read.err = failure
				}
				steps = append(steps, read)
				if mode != "reminders-failed" {
					digest := queryStep("SELECT EXISTS(SELECT 1 FROM reminder_broadcast_state", sqlValues(true))
					digest.check = func(a []driver.NamedValue) {
						if len(a) != 1 || len(a[0].Value.(string)) != 64 || strings.Contains(a[0].Value.(string), "提醒") {
							t.Fatal("state digest contains reminder content", a)
						}
					}
					if mode == "digest-failed" {
						digest.err = failure
					}
					steps = append(steps, digest)
				}
				steps = append(steps, terminalStep("begin"), execStep("UPDATE runtime_lease SET owner=null"), terminalStep("commit"))
			}
			events := calendarFixture(t, steps...)
			locks, err := coordination.New(events.pool)
			if err != nil {
				t.Fatal(err)
			}
			defer locks.Close(context.Background())
			var sent atomic.Uint64
			// A nil AMQP channel makes any accidental publication fail immediately.
			err = events.publishChange(context.Background(), nil, nil, locks, &sent)
			if (mode == "acquire-failed" || mode == "reminders-failed" || mode == "digest-failed") != errors.Is(err, failure) || sent.Load() != 0 {
				t.Fatal(mode, err, sent.Load())
			}
		})
	}
}
