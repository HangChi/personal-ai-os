package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net"
	"regexp"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

const ReviewQueue = "personalos.knowledge.review"
const SearchQueue = "personalos.search.project"

type SignalPublisher interface {
	Publish(context.Context, string, string) error
}
type SignalRelay struct {
	Store      *Store
	Publisher  SignalPublisher
	IndexQueue string
	Stats      *Stats
}

func (r *SignalRelay) Relay(ctx context.Context) error {
	lease, e := r.Store.Locks.Acquire(ctx, "work-signal-relay", 1)
	if e != nil || lease == nil {
		return e
	}
	defer func() {
		c, cancel := context.WithTimeout(context.WithoutCancel(ctx), time.Second)
		defer cancel()
		_ = lease.Release(c)
	}()
	type signal struct {
		topic, id string
		revision  int64
	}
	items := []signal{}
	e = r.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT topic,item_id::text,revision FROM work_signal_outbox WHERE next_publish<=now() ORDER BY next_publish LIMIT 20")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var s signal
			if e = rows.Scan(&s.topic, &s.id, &s.revision); e != nil {
				return e
			}
			items = append(items, s)
		}
		return rows.Err()
	})
	if e != nil {
		return e
	}
	queues := map[string]string{"index": r.IndexQueue, "review": ReviewQueue, "search": SearchQueue}
	for _, s := range items {
		c, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
		queue, ok := queues[s.topic]
		var publishErr error
		if !ok {
			publishErr = errors.New("未知任务唤醒主题")
		} else {
			publishErr = r.Publisher.Publish(c, queue, s.id)
		}
		cancel()
		delay, result := 30, "confirmed"
		if publishErr != nil {
			delay, result = 5, "retry"
		}
		if r.Stats != nil {
			r.Stats.Count("work.signals."+s.topic, result)
		}
		e = r.Store.Pool.WithTx(database.WithWriteGuard(ctx, lease.Fence), sql.TxOptions{}, func(tx *sql.Tx) error {
			_, e := tx.ExecContext(ctx, "UPDATE work_signal_outbox SET next_publish=now()+$1*interval '1 second' WHERE topic=$2 AND item_id=$3 AND revision=$4", delay, s.topic, s.id, s.revision)
			return e
		})
		if e != nil {
			return e
		}
		if publishErr != nil {
			return publishErr
		}
	}
	return nil
}

type channelPublisher struct {
	channel *amqp.Channel
	returns <-chan amqp.Return
}

func (p channelPublisher) Publish(ctx context.Context, queue, id string) error {
	receipt, e := p.channel.PublishWithDeferredConfirmWithContext(ctx, "", queue, true, false, amqp.Publishing{DeliveryMode: amqp.Persistent, ContentType: "text/plain", Body: []byte(id)})
	if e != nil {
		return e
	}
	ack, e := receipt.WaitContext(ctx)
	if e != nil || !ack {
		return errors.New("任务唤醒未确认")
	}
	select {
	case <-p.returns:
		return errors.New("任务唤醒无法路由")
	default:
		return nil
	}
}

type Signals struct {
	cancel context.CancelFunc
	wg     sync.WaitGroup
	Stats  *Stats
}

// Wakeups are hints. Each role also polls the database independently.
func StartSignals(parent context.Context, address, indexQueue string, store *Store, publish bool, wakeups map[string]func(), stats *Stats, logger *slog.Logger) (*Signals, error) {
	u, e := amqp.ParseURI(address)
	if e != nil || (u.Scheme != "amqp" && u.Scheme != "amqps") || !regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,100}$`).MatchString(indexQueue) {
		return nil, errors.New("RabbitMQ 唤醒配置非法")
	}
	ctx, cancel := context.WithCancel(parent)
	s := &Signals{cancel: cancel, Stats: stats}
	start := func(run func(context.Context, *amqp.Channel) error) {
		s.wg.Go(func() {
			for ctx.Err() == nil {
				e := rabbitConnection(ctx, address, indexQueue, run)
				if ctx.Err() != nil {
					return
				}
				if e != nil {
					if stats != nil {
						stats.Count("work.connections", "retry")
					}
					logger.WarnContext(ctx, "任务唤醒连接中断，将重试")
				}
				select {
				case <-ctx.Done():
					return
				case <-time.After(time.Second):
				}
			}
		})
	}
	if publish {
		start(func(ctx context.Context, ch *amqp.Channel) error {
			if e := ch.Confirm(false); e != nil {
				return e
			}
			relay := &SignalRelay{Store: store, IndexQueue: indexQueue, Stats: stats, Publisher: channelPublisher{ch, ch.NotifyReturn(make(chan amqp.Return, 1))}}
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-ticker.C:
					if e := relay.Relay(ctx); e != nil {
						return e
					}
				}
			}
		})
	}
	for queue, wakeup := range wakeups {
		start(func(ctx context.Context, ch *amqp.Channel) error {
			if e := ch.Qos(1, 0, false); e != nil {
				return e
			}
			deliveries, e := ch.ConsumeWithContext(ctx, queue, "", false, false, false, false, nil)
			if e != nil {
				return e
			}
			for d := range deliveries {
				if _, ok := api.ParseJavaUUID(string(d.Body)); ok {
					wakeup()
					if stats != nil {
						stats.Count("work.wakeups", "received")
					}
				} else if stats != nil {
					stats.Count("work.wakeups", "invalid")
				}
				if e := d.Ack(false); e != nil {
					return e
				}
			}
			return errors.New("任务唤醒订阅关闭")
		})
	}
	return s, nil
}
func rabbitConnection(ctx context.Context, address, indexQueue string, run func(context.Context, *amqp.Channel) error) error {
	var onCancel func() bool
	defer func() {
		if onCancel != nil {
			onCancel()
		}
	}()
	conn, e := amqp.DialConfig(address, amqp.Config{Heartbeat: 10 * time.Second, Dial: func(network, address string) (net.Conn, error) {
		d := net.Dialer{Timeout: 5 * time.Second}
		c, e := d.DialContext(ctx, network, address)
		if e == nil {
			_ = c.SetDeadline(time.Now().Add(5 * time.Second))
			onCancel = context.AfterFunc(ctx, func() { _ = c.Close() })
		}
		return c, e
	}})
	if e != nil {
		return e
	}
	defer conn.CloseDeadline(time.Now().Add(time.Second))
	ch, e := conn.Channel()
	if e != nil {
		return e
	}
	for _, name := range []string{indexQueue, ReviewQueue, SearchQueue} {
		if _, e = ch.QueueDeclare(name, true, false, false, false, amqp.Table{"x-message-ttl": int32(60000), "x-max-length": int32(1000)}); e != nil {
			return e
		}
	}
	return run(ctx, ch)
}
func (s *Signals) Close() { s.cancel(); s.wg.Wait() }
