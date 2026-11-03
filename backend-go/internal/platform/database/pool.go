// Package database 管理显式 SQL 连接与事务边界，不自动迁移或访问默认数据库。
package database

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"time"
)

type PoolOptions struct {
	MaxOpen           int
	MaxIdle           int
	ConnectionTimeout time.Duration
	MaxLifetime       time.Duration
	MaxIdleTime       time.Duration
}

// DefaultPoolOptions 对齐 Java API/combined 池上限 10、Worker 4、等待 5s。
// 事务与查询期限由调用方 context 决定，不复用连接获取的短 context。
func DefaultPoolOptions(worker bool) PoolOptions {
	size := 10
	if worker {
		size = 4
	}
	return PoolOptions{MaxOpen: size, MaxIdle: size, ConnectionTimeout: 5 * time.Second, MaxLifetime: 30 * time.Minute}
}

func (options PoolOptions) validate() error {
	if options.MaxOpen <= 0 {
		return errors.New("数据库 MaxOpen 必须为正数")
	}
	if options.MaxIdle < 0 || options.MaxIdle > options.MaxOpen {
		return errors.New("数据库 MaxIdle 必须在 0 与 MaxOpen 之间")
	}
	if options.ConnectionTimeout <= 0 {
		return errors.New("数据库 ConnectionTimeout 必须为正时长")
	}
	if options.MaxLifetime < 0 || options.MaxIdleTime < 0 {
		return errors.New("数据库连接生命周期与空闲期限不能为负")
	}
	return nil
}

type Pool struct {
	db                *sql.DB
	connectionTimeout time.Duration
}

// Open 只使用调用方显式提供的 Connector，校验和探测失败时关闭新建池。
// 生产 Connector 必须支持 context 取消；当前尚未接入 PostgreSQL 驱动。
func Open(ctx context.Context, connector driver.Connector, options PoolOptions) (*Pool, error) {
	if ctx == nil {
		return nil, errors.New("数据库 context 不能为空")
	}
	if connector == nil {
		return nil, errors.New("数据库 Connector 不能为空")
	}
	if err := options.validate(); err != nil {
		return nil, err
	}
	db := sql.OpenDB(connector)
	db.SetMaxOpenConns(options.MaxOpen)
	db.SetMaxIdleConns(options.MaxIdle)
	db.SetConnMaxLifetime(options.MaxLifetime)
	db.SetConnMaxIdleTime(options.MaxIdleTime)
	pool := &Pool{db: db, connectionTimeout: options.ConnectionTimeout}
	if err := pool.Ping(ctx); err != nil {
		return nil, combine(err, operationError("关闭", db.Close()))
	}
	return pool, nil
}

func (pool *Pool) Stats() sql.DBStats { return pool.db.Stats() }

// Close 幂等；应先取消 Worker/请求并等待其退出，再关闭池。
// 不以强制中断或成功占位掩盖未结束事务。
func (pool *Pool) Close() error { return operationError("关闭", pool.db.Close()) }

// Ping 为池等待与驱动探测共同设置上限，不改变父 context。
func (pool *Pool) Ping(ctx context.Context) error {
	if ctx == nil {
		return errors.New("数据库 context 不能为空")
	}
	pingCtx, cancel := context.WithTimeout(ctx, pool.connectionTimeout)
	defer cancel()
	return operationError("健康检查", pool.db.PingContext(pingCtx))
}

// WithConn 为回调提供独占连接，正常、错误与 panic 路径都归还。
// 回调必须使用调用方 ctx 完成 SQL，不得让 Rows/连接逃逸到回调之外。
func (pool *Pool) WithConn(ctx context.Context, callback func(*sql.Conn) error) (err error) {
	if ctx == nil {
		return errors.New("数据库 context 不能为空")
	}
	if callback == nil {
		return errors.New("数据库连接回调不能为空")
	}
	acquireCtx, cancel := context.WithTimeout(ctx, pool.connectionTimeout)
	connection, acquireErr := pool.db.Conn(acquireCtx)
	cancel()
	if acquireErr != nil {
		return operationError("获取连接", acquireErr)
	}
	defer func() {
		closeErr := connection.Close()
		if !errors.Is(closeErr, sql.ErrConnDone) {
			err = combine(err, operationError("归还连接", closeErr))
		}
	}()
	return callback(connection)
}

// WithTx 在独占连接中开始事务；回调成功才提交，错误或 panic 均回滚。
// 需要加入同一事务时向仓储显式传递 tx；再次调用 WithTx 是独立事务，
// 会占用另一连接并遵守池等待上限。不会自动重试未知结果的提交。
func (pool *Pool) WithTx(ctx context.Context, options sql.TxOptions, callback func(*sql.Tx) error) error {
	if callback == nil {
		return errors.New("数据库事务回调不能为空")
	}
	return pool.WithConn(ctx, func(connection *sql.Conn) (err error) {
		tx, beginErr := connection.BeginTx(ctx, &options)
		if beginErr != nil {
			return operationError("开始事务", beginErr)
		}
		defer func() {
			rollbackErr := tx.Rollback()
			if !errors.Is(rollbackErr, sql.ErrTxDone) {
				err = combine(err, operationError("回滚", rollbackErr))
			}
		}()
		if err = callback(tx); err != nil {
			return err
		}
		if err = ctx.Err(); err != nil {
			return operationError("提交", err)
		}
		return operationError("提交", tx.Commit())
	})
}

// Error 不在日志/普通格式化中暴露 SQL、DSN 或驱动原始消息。
// 业务层可用 errors.Is/As 检查原因，对外仍应映射统一错误码。
type Error struct {
	operation string
	cause     error
}

func (err *Error) Error() string     { return "数据库" + err.operation + "失败" }
func (err *Error) GoString() string  { return err.Error() }
func (err *Error) Unwrap() error     { return err.cause }
func (err *Error) Operation() string { return err.operation }

func operationError(operation string, cause error) error {
	if cause == nil {
		return nil
	}
	return &Error{operation: operation, cause: cause}
}

func combine(original, cleanup error) error {
	if original == nil {
		return cleanup
	}
	if cleanup == nil {
		return original
	}
	return errors.Join(original, cleanup)
}
