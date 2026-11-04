package calendar

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

const broadcastExchange = "personalos.reminders.changed"

type Broadcast struct {
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

func StartBroadcast(parent context.Context, address string, events *Events, stream *Stream, coordinator *coordination.Coordinator, publish bool, logger *slog.Logger) (*Broadcast, error) {
	uri, err := amqp.ParseURI(address)
	if err != nil || (uri.Scheme != "amqp" && uri.Scheme != "amqps") {
		return nil, errors.New("提醒广播的 RabbitMQ URI 非法")
	}
	ctx, cancel := context.WithCancel(parent)
	b := &Broadcast{cancel: cancel}
	start := func(run func(context.Context, *amqp.Channel) error) {
		b.wg.Go(func() {
			for ctx.Err() == nil {
				err := withRabbit(ctx, address, run)
				if ctx.Err() != nil {
					return
				}
				if err != nil {
					logger.WarnContext(ctx, "提醒广播连接中断，将重试")
				}
				select {
				case <-ctx.Done():
					return
				case <-time.After(time.Second):
				}
			}
		})
	}
	if stream != nil {
		start(func(ctx context.Context, channel *amqp.Channel) error {
			queue, err := channel.QueueDeclare("", false, true, true, false, amqp.Table{"x-max-length": int32(10)})
			if err != nil {
				return err
			}
			if err := channel.QueueBind(queue.Name, "", broadcastExchange, false, nil); err != nil {
				return err
			}
			if err := channel.Qos(1, 0, false); err != nil {
				return err
			}
			deliveries, err := channel.ConsumeWithContext(ctx, queue.Name, "", false, true, false, false, nil)
			if err != nil {
				return err
			}
			stream.Notify()
			for delivery := range deliveries {
				stream.Notify()
				if err := delivery.Ack(false); err != nil {
					return err
				}
			}
			return errors.New("提醒广播订阅关闭")
		})
	}
	if publish {
		start(func(ctx context.Context, channel *amqp.Channel) error {
			if err := channel.Confirm(false); err != nil {
				return err
			}
			returns := channel.NotifyReturn(make(chan amqp.Return, 1))
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-ticker.C:
					if err := events.publishChange(ctx, channel, returns, coordinator); err != nil {
						return err
					}
				}
			}
		})
	}
	return b, nil
}

func (b *Broadcast) Close() {
	b.cancel()
	b.wg.Wait()
}

func withRabbit(ctx context.Context, address string, run func(context.Context, *amqp.Channel) error) error {
	var closeOnCancel func() bool
	defer func() {
		if closeOnCancel != nil {
			closeOnCancel()
		}
	}()
	connection, err := amqp.DialConfig(address, amqp.Config{
		Heartbeat: 10 * time.Second,
		Dial: func(network, address string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 5 * time.Second}
			conn, err := dialer.DialContext(ctx, network, address)
			if err != nil {
				return nil, err
			}
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			closeOnCancel = context.AfterFunc(ctx, func() { _ = conn.Close() })
			return conn, nil
		},
	})
	if err != nil {
		return err
	}
	defer func() { _ = connection.CloseDeadline(time.Now().Add(time.Second)) }()
	channel, err := connection.Channel()
	if err != nil {
		return err
	}
	if err := channel.ExchangeDeclare(broadcastExchange, "fanout", true, false, false, false, nil); err != nil {
		return err
	}
	return run(ctx, channel)
}

func (s *Events) publishChange(ctx context.Context, channel *amqp.Channel, returns <-chan amqp.Return, coordinator *coordination.Coordinator) error {
	lease, err := coordinator.Acquire(ctx, "reminder-broadcast", 1)
	if err != nil || lease == nil {
		return err
	}
	defer func() {
		releaseCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), time.Second)
		defer cancel()
		_ = lease.Release(releaseCtx)
	}()
	items, err := s.Reminders(ctx, "", true, 500, time.Now())
	if err != nil {
		return err
	}
	payload, err := json.Marshal(items)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(payload)
	digest := hex.EncodeToString(hash[:])
	var unchanged bool
	if err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM reminder_broadcast_state WHERE id=1 AND digest=$1)", digest).Scan(&unchanged)
	}); err != nil || unchanged {
		return err
	}
	confirmCtx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	receipt, err := channel.PublishWithDeferredConfirmWithContext(confirmCtx, broadcastExchange, "", true, false,
		amqp.Publishing{ContentType: "text/plain", Body: []byte("changed")})
	if err != nil {
		return err
	}
	acked, err := receipt.WaitContext(confirmCtx)
	if err != nil || !acked {
		return errors.New("提醒广播未确认")
	}
	select {
	case <-returns:
		return errors.New("提醒广播无法路由")
	default:
	}
	return s.pool.WithTx(database.WithWriteGuard(ctx, lease.Fence), sql.TxOptions{}, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `INSERT INTO reminder_broadcast_state(id,digest) VALUES(1,$1)
			ON CONFLICT(id) DO UPDATE SET digest=excluded.digest`, digest)
		return err
	})
}
