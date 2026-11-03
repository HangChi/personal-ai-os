// Package coordination 移植 DatabaseCoordination 的数据库租约，不自动创建表。
package coordination

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"personal-ai-os/backend-go/internal/platform/database"
)

var (
	ErrClosed                   = errors.New("数据库协调器已关闭")
	ErrLeaseLost                = errors.New("文档写入租约已丢失")
	ErrWriteTransactionRequired = errors.New("fencing 需要活动写事务")
)

type Coordinator struct {
	pool                    *database.Pool
	ctx                     context.Context
	cancel                  context.CancelFunc
	mu                      sync.Mutex
	held                    map[string]*Lease
	closed                  bool
	active                  sync.WaitGroup
	renewDone, shutdownDone chan struct{}
	closeOnce               sync.Once
	closeErr                error
}

func New(pool *database.Pool) (*Coordinator, error) {
	if pool == nil {
		return nil, errors.New("数据库协调器必须显式提供连接池")
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := &Coordinator{pool: pool, ctx: ctx, cancel: cancel, held: make(map[string]*Lease), renewDone: make(chan struct{}), shutdownDone: make(chan struct{})}
	go c.run()
	return c, nil
}

func (c *Coordinator) run() {
	defer close(c.renewDone)
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
			_ = c.Renew(ctx)
			cancel()
		}
	}
}

func validateAcquire(resource string, capacity int) error {
	if !utf8.ValidString(resource) || strings.ContainsRune(resource, '\x00') || len(utf16.Encode([]rune(resource))) > 160 || capacity < 1 || capacity > 100 {
		return errors.New("租约资源必须为至多 160 个 UTF-16 单元，容量必须为 1–100")
	}
	return nil
}

// Acquire 使用独立短事务、数据库时钟与 SKIP LOCKED；无空位返回 nil,nil。
// 持有期间不占用数据库连接；租期 120 秒，每 10 秒尝试续租。
func (c *Coordinator) Acquire(parent context.Context, resource string, capacity int) (*Lease, error) {
	if parent == nil {
		return nil, errors.New("租约 context 不能为空")
	}
	if err := validateAcquire(resource, capacity); err != nil {
		return nil, err
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, ErrClosed
	}
	c.active.Add(1)
	c.mu.Unlock()
	defer c.active.Done()
	ctx, cancel := context.WithCancel(parent)
	stop := context.AfterFunc(c.ctx, cancel)
	defer stop()
	defer cancel()
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return nil, wrap("生成所有者", err)
	}
	id[6] = (id[6] & 15) | 64
	id[8] = (id[8] & 63) | 128
	owner := fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:])
	slot, found := 0, false
	err := c.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO runtime_lease(resource,slot)
			SELECT $1,generate_series(0,$2::integer-1) ON CONFLICT DO NOTHING`, resource, capacity); err != nil {
			return err
		}
		err := tx.QueryRowContext(ctx, `WITH ready AS (SELECT slot FROM runtime_lease
			WHERE resource=$1 AND slot<$2 AND lease_until<clock_timestamp()
			ORDER BY slot FOR UPDATE SKIP LOCKED LIMIT 1)
			UPDATE runtime_lease l SET owner=$3::uuid,lease_until=clock_timestamp()+interval '120 seconds'
			FROM ready r WHERE l.resource=$1 AND l.slot=r.slot RETURNING l.slot`, resource, capacity, owner).Scan(&slot)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		found = err == nil
		return err
	})
	if err != nil {
		return nil, wrap("获取", err)
	}
	if !found {
		return nil, nil
	}
	lease := &Lease{coordinator: c, resource: resource, slot: slot, owner: owner}
	lease.valid.Store(true)
	c.mu.Lock()
	c.held[owner] = lease
	c.mu.Unlock()
	return lease, nil
}

func (c *Coordinator) snapshot() []*Lease {
	c.mu.Lock()
	defer c.mu.Unlock()
	leases := make([]*Lease, 0, len(c.held))
	for _, lease := range c.held {
		leases = append(leases, lease)
	}
	return leases
}

// Renew 任一续租失败即撤销本地信任；后续成功也不把失效租约恢复为有效。
func (c *Coordinator) Renew(ctx context.Context) error {
	if ctx == nil {
		return errors.New("续租 context 不能为空")
	}
	var failures error
	for _, lease := range c.snapshot() {
		err := c.pool.WithConn(ctx, func(conn *sql.Conn) error {
			result, err := conn.ExecContext(ctx, `UPDATE runtime_lease SET lease_until=clock_timestamp()+interval '120 seconds'
				WHERE resource=$1 AND slot=$2 AND owner=$3::uuid AND lease_until>=clock_timestamp()`, lease.resource, lease.slot, lease.owner)
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err == nil && count != 1 {
				lease.valid.Store(false)
			}
			return err
		})
		if err != nil {
			lease.valid.Store(false)
			failures = errors.Join(failures, wrap("续租", err))
		}
	}
	return failures
}

// Close 停止续租并取消在途获取，等待其归档后释放持有租约；不关闭调用方池。
// 清理独立于请求取消且最多 5 秒；数据库失败时不信任本地所有权，等待自然过期。
func (c *Coordinator) Close(ctx context.Context) error {
	if ctx == nil {
		return errors.New("关闭 context 不能为空")
	}
	c.closeOnce.Do(func() {
		c.mu.Lock()
		c.closed = true
		c.cancel()
		c.mu.Unlock()
		go func() {
			<-c.renewDone
			c.active.Wait()
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			for _, lease := range c.snapshot() {
				c.closeErr = errors.Join(c.closeErr, lease.Release(cleanup))
			}
			close(c.shutdownDone)
		}()
	})
	select {
	case <-c.shutdownDone:
		return c.closeErr
	case <-ctx.Done():
		return ctx.Err()
	}
}

type Lease struct {
	coordinator     *Coordinator
	resource, owner string
	slot            int
	valid, closed   atomic.Bool
	closeMu         sync.Mutex
}

func (*Lease) String() string    { return "Lease{redacted}" }
func (*Lease) GoString() string  { return "Lease{redacted}" }
func (lease *Lease) Valid() bool { return lease != nil && lease.valid.Load() && !lease.closed.Load() }

// Fence 必须在最终写事务内调用并处理错误，行锁保留到该事务结束。
func (lease *Lease) Fence(ctx context.Context, tx *database.Transaction) error {
	if ctx == nil {
		return errors.New("fencing context 不能为空")
	}
	if !tx.Writable() {
		return ErrWriteTransactionRequired
	}
	if !lease.Valid() {
		return ErrLeaseLost
	}
	raw := tx.SQL()
	if raw == nil {
		return ErrWriteTransactionRequired
	}
	var slot int
	err := raw.QueryRowContext(ctx, `SELECT slot FROM runtime_lease WHERE resource=$1 AND slot=$2
		AND owner=$3::uuid AND lease_until>=clock_timestamp() FOR UPDATE`, lease.resource, lease.slot, lease.owner).Scan(&slot)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrLeaseLost
	}
	return wrap("写入栅栏", err)
}

func (lease *Lease) Release(ctx context.Context) error {
	if ctx == nil {
		return errors.New("释放 context 不能为空")
	}
	lease.closeMu.Lock()
	if lease.closed.Load() {
		lease.closeMu.Unlock()
		return nil
	}
	lease.closed.Store(true)
	lease.closeMu.Unlock()
	return lease.release(ctx)
}

// ReleaseAfter 显式替代 Spring 的 afterCompletion；提交/回滚/panic 都会释放。
// 注册失败不关闭租约；注册成功后不能再 fencing，也不能提前 Release。
func (lease *Lease) ReleaseAfter(tx *database.Transaction) error {
	lease.closeMu.Lock()
	defer lease.closeMu.Unlock()
	if lease.closed.Load() {
		return nil
	}
	if err := tx.AfterCompletion(func() error {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return lease.release(ctx)
	}); err != nil {
		return err
	}
	lease.closed.Store(true)
	return nil
}

func (lease *Lease) release(ctx context.Context) error {
	c := lease.coordinator
	c.mu.Lock()
	delete(c.held, lease.owner)
	c.mu.Unlock()
	return wrap("释放", c.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `UPDATE runtime_lease SET owner=null,lease_until='epoch'
			WHERE resource=$1 AND slot=$2 AND owner=$3::uuid`, lease.resource, lease.slot, lease.owner)
		return err
	}))
}

type Error struct {
	operation string
	cause     error
}

func (err *Error) Error() string    { return "数据库租约" + err.operation + "失败" }
func (err *Error) GoString() string { return err.Error() }
func (err *Error) Unwrap() error    { return err.cause }
func wrap(operation string, err error) error {
	if err == nil {
		return nil
	}
	return &Error{operation: operation, cause: err}
}
