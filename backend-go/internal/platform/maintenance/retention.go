// Package maintenance owns opt-in usage and audit retention, independent of business writes.
package maintenance

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/schedule"
)

type Store interface {
	DeleteUsage(context.Context, time.Time) (int64, error)
	DeleteOperations(context.Context, time.Time) (int64, error)
}

type PostgresStore struct{ pool *database.Pool }

func NewPostgresStore(pool *database.Pool) *PostgresStore { return &PostgresStore{pool: pool} }
func (s *PostgresStore) delete(ctx context.Context, query string, cutoff time.Time) (count int64, err error) {
	if s == nil || s.pool == nil {
		return 0, errors.New("保留策略需要数据库")
	}
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		result, e := tx.ExecContext(ctx, query, cutoff)
		if e != nil {
			return e
		}
		count, e = result.RowsAffected()
		return e
	})
	return count, err
}
func (s *PostgresStore) DeleteUsage(ctx context.Context, cutoff time.Time) (int64, error) {
	return s.delete(ctx, "DELETE FROM token_usage WHERE created_at < $1", cutoff)
}
func (s *PostgresStore) DeleteOperations(ctx context.Context, cutoff time.Time) (int64, error) {
	// 不确定的外部副作用仍需人工核查；保留其所有权，不能因审计保留期重新执行。
	return s.delete(ctx, "DELETE FROM operation_record WHERE created_at < $1 AND NOT (source='TOOL' AND outcome='IN_PROGRESS')", cutoff)
}

type Service struct {
	store    Store
	settings config.RetentionProperties
	now      func() time.Time
}
type Counts struct{ Usage, Operations int64 }

func New(store Store, settings config.RetentionProperties, now func() time.Time) (*Service, error) {
	if store == nil {
		return nil, errors.New("保留策略仓储不能为空")
	}
	if settings.TokenUsageDays < 0 || settings.OperationRecordDays < 0 {
		return nil, errors.New("数据保留天数不能为负数（0 表示永久保留）")
	}
	if now == nil {
		now = time.Now
	}
	return &Service{store: store, settings: settings, now: now}, nil
}
func (s *Service) Cleanup(ctx context.Context) (Counts, error) {
	var counts Counts
	if ctx == nil {
		return counts, errors.New("保留策略context不能为空")
	}
	if err := ctx.Err(); err != nil {
		return counts, err
	}
	now := s.now().UTC()
	var err error
	// AddDate on UTC avoids both DST and time.Duration overflow for Java int days.
	if s.settings.TokenUsageDays > 0 {
		counts.Usage, err = s.store.DeleteUsage(ctx, now.AddDate(0, 0, -s.settings.TokenUsageDays))
		if err != nil {
			return counts, err
		}
	}
	if s.settings.OperationRecordDays > 0 {
		counts.Operations, err = s.store.DeleteOperations(ctx, now.AddDate(0, 0, -s.settings.OperationRecordDays))
	}
	return counts, err
}

type Worker struct {
	cancel context.CancelFunc
	done   chan struct{}
	once   sync.Once
}

func ParseCron(expression string) (schedule.Cron, error) { return schedule.Parse(expression) }
func Start(ctx context.Context, settings config.RetentionProperties, store Store, logger *slog.Logger) (*Worker, error) {
	if ctx == nil || logger == nil {
		return nil, errors.New("保留策略需要context与日志")
	}
	cron, err := ParseCron(settings.Cron)
	if err != nil {
		return nil, err
	}
	service, err := New(store, settings, nil)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	w := &Worker{cancel: cancel, done: make(chan struct{})}
	if cron.Disabled() || settings.TokenUsageDays == 0 && settings.OperationRecordDays == 0 {
		close(w.done)
		return w, nil
	}
	go func() {
		defer close(w.done)
		timer := time.NewTicker(time.Second)
		defer timer.Stop()
		run(ctx, cron, time.Local, timer.C, func(parent context.Context) {
			bounded, stop := context.WithTimeout(parent, 30*time.Second)
			defer stop()
			counts, err := service.Cleanup(bounded)
			if err != nil {
				if parent.Err() == nil {
					logger.Error("保留策略清理失败")
				}
				return
			}
			logger.Info("保留策略清理完成", "usage", counts.Usage, "operations", counts.Operations)
		})
	}()
	return w, nil
}
func run(ctx context.Context, cron schedule.Cron, zone *time.Location, ticks <-chan time.Time, cleanup func(context.Context)) {
	var previous time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case now, ok := <-ticks:
			if !ok || ctx.Err() != nil {
				return
			}
			second := now.Truncate(time.Second)
			if !second.Equal(previous) && cron.Matches(now.In(zone)) {
				previous = second
				cleanup(ctx)
			}
		}
	}
}
func (w *Worker) Close() { w.once.Do(w.cancel); <-w.done }
