package ratelimit

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

// PostgresBucket 对应 DatabaseCoordination.consume；不负责租约、建表或重试。
// 每次消费都使用独立短事务，以数据库时钟和条件 UPDATE 保证共享预算。
type PostgresBucket struct{ pool *database.Pool }

var _ Bucket = (*PostgresBucket)(nil)

func NewPostgresBucket(pool *database.Pool) (*PostgresBucket, error) {
	if pool == nil {
		return nil, errors.New("数据库共享限流必须显式提供连接池")
	}
	return &PostgresBucket{pool: pool}, nil
}

// Consume 保留 Java 的 INSERT ON CONFLICT、分数补充、容量裁剪和拒绝不更新。
// 此事务不加入调用方业务事务，后续业务回滚不会返还已消费令牌。
// 提交结果不明确时返回错误和 false，绝不重试或回退本地桶。
func (bucket *PostgresBucket) Consume(ctx context.Context, name string, capacity int, period time.Duration) (bool, error) {
	if err := (Options{Capacity: capacity, Period: period}).Validate(); err != nil {
		return false, err
	}
	allowed := false
	err := bucket.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO runtime_rate_bucket(name,tokens)
			VALUES ($1,$2) ON CONFLICT DO NOTHING`, name, capacity); err != nil {
			return err
		}
		rate := float64(capacity) / (float64(period) / float64(time.Second))
		var consumed string
		err := tx.QueryRowContext(ctx, `UPDATE runtime_rate_bucket
			SET tokens=least($1,tokens+
				greatest(0,extract(epoch FROM clock_timestamp()-refilled_at))*$2)-1,
				refilled_at=clock_timestamp()
			WHERE name=$3 AND least($1,tokens+
				greatest(0,extract(epoch FROM clock_timestamp()-refilled_at))*$2)>=1
			RETURNING name`, capacity, rate, name).Scan(&consumed)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		allowed = true
		return nil
	})
	if err != nil {
		return false, &postgresBucketError{cause: err}
	}
	return allowed, nil
}

type postgresBucketError struct{ cause error }

func (*postgresBucketError) Error() string        { return "数据库共享限流消费失败" }
func (err *postgresBucketError) GoString() string { return err.Error() }
func (err *postgresBucketError) Unwrap() error    { return err.cause }
