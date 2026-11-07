package knowledge

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

type signalLogHandler struct {
	messages chan string
	attrs    []slog.Attr
}

func (signalLogHandler) Enabled(context.Context, slog.Level) bool { return true }
func (h signalLogHandler) Handle(_ context.Context, r slog.Record) error {
	message := r.Message
	appendAttr := func(a slog.Attr) bool {
		message += fmt.Sprint(" ", a.Key, "=", a.Value.Resolve())
		return true
	}
	for _, a := range h.attrs {
		appendAttr(a)
	}
	r.Attrs(appendAttr)
	select {
	case h.messages <- message:
	default:
	}
	return nil
}
func (h signalLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	h.attrs = append(append([]slog.Attr{}, h.attrs...), attrs...)
	return h
}
func (h signalLogHandler) WithGroup(string) slog.Handler { return h }

func TestSignalsRejectInvalidConfigurationAndCloseUnusedOrCancelledRoles(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, tc := range []struct{ address, queue string }{{"http://127.0.0.1", "index"}, {"amqp://bad host", "index"}, {"amqp://127.0.0.1", ""}, {"amqp://127.0.0.1", "invalid queue"}, {"amqp://127.0.0.1", strings.Repeat("x", 101)}} {
		if signals, err := StartSignals(context.Background(), tc.address, tc.queue, nil, false, nil, nil, logger); err == nil || signals != nil {
			t.Fatal("invalid signal configuration accepted", tc, err)
		}
	}
	for _, cancelled := range []bool{false, true} {
		ctx, cancel := context.WithCancel(context.Background())
		if cancelled {
			cancel()
		}
		signals, err := StartSignals(ctx, "amqp://127.0.0.1:1", "fixture.index", nil, cancelled, map[string]func(){ReviewQueue: func() { t.Error("cancelled subscriber woke worker") }}, nil, logger)
		if err != nil {
			t.Fatal(err)
		}
		signals.Close()
		signals.Close()
		cancel()
	}
}

func TestSignalConnectionCancelsIncompleteHandshakeAndLogsRetryWithoutCredentials(t *testing.T) {
	for _, mode := range []string{"retry", "cancel-handshake"} {
		t.Run(mode, func(t *testing.T) {
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
				if mode == "cancel-handshake" {
					_, _ = io.Copy(io.Discard, conn)
				}
			}()
			address := "amqp://fixture-user:synthetic-secret@" + listener.Addr().String()
			if mode == "cancel-handshake" {
				done := make(chan error, 1)
				go func() {
					done <- rabbitConnection(ctx, address, "fixture.index", func(context.Context, *amqp.Channel) error {
						t.Error("unestablished connection invoked relay")
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
					t.Fatal("handshake ignored cancellation")
				}
			} else {
				messages := make(chan string, 2)
				stats := NewStats()
				signals, err := StartSignals(ctx, address, "fixture.index", nil, true, nil, stats, slog.New(signalLogHandler{messages: messages}))
				if err != nil {
					t.Fatal(err)
				}
				defer signals.Close()
				select {
				case message := <-messages:
					if strings.Contains(message, "synthetic-secret") || !strings.Contains(message, "重试") {
						t.Fatal(message)
					}
				case <-time.After(2 * time.Second):
					t.Fatal("connection failure did not enter retry")
				}
				counts, _ := stats.Snapshot()
				if counts["work.connections|retry"] == 0 {
					t.Fatal(counts)
				}
				signals.Close()
			}
			select {
			case <-serverDone:
			case <-time.After(time.Second):
				t.Fatal("test connection leaked")
			}
		})
	}
}
