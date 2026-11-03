package database

import (
	"context"
	"database/sql"
	"errors"
	"sync"
)

type writeGuardKey struct{}

// WithWriteGuard 为显式后台执行传播最终写入栅栏；只在写事务内校验。
// 它不创建环境事务，不重试回调，租约行锁随每个短事务提交释放。
func WithWriteGuard(ctx context.Context, guard func(context.Context, *Transaction) error) context.Context {
	return context.WithValue(ctx, writeGuardKey{}, guard)
}

// Transaction 是显式事务作用域。SQL 不得逃逸或由回调自行 Commit/Rollback；
// 完成回调在数据库事务结束且连接归还后执行，成功、错误与 panic 都会执行。
type Transaction struct {
	mu               sync.Mutex
	sql              *sql.Tx
	active, readOnly bool
	completion       []func() error
}

func (tx *Transaction) SQL() *sql.Tx {
	if tx == nil {
		return nil
	}
	tx.mu.Lock()
	defer tx.mu.Unlock()
	if !tx.active {
		return nil
	}
	return tx.sql
}

func (tx *Transaction) Writable() bool {
	if tx == nil {
		return false
	}
	tx.mu.Lock()
	defer tx.mu.Unlock()
	return tx.active && !tx.readOnly
}

func (tx *Transaction) AfterCompletion(callback func() error) error {
	if tx == nil || callback == nil {
		return errors.New("事务完成回调需要活动事务与非空函数")
	}
	tx.mu.Lock()
	defer tx.mu.Unlock()
	if !tx.active {
		return errors.New("事务已经结束，不能注册完成回调")
	}
	tx.completion = append(tx.completion, callback)
	return nil
}

// WithManagedTx 保持 WithTx 的同连接/独立事务/不重试约定，不使用环境事务。
func (pool *Pool) WithManagedTx(ctx context.Context, options sql.TxOptions, callback func(*Transaction) error) (err error) {
	if callback == nil {
		return errors.New("数据库事务回调不能为空")
	}
	var managed *Transaction
	defer func() {
		original := recover()
		var completionPanic any
		if managed != nil {
			var completionErr error
			completionErr, completionPanic = managed.complete()
			err = combine(err, operationError("事务完成回调", completionErr))
		}
		if original != nil {
			panic(original)
		}
		if completionPanic != nil {
			panic(completionPanic)
		}
	}()
	return pool.WithTx(ctx, options, func(raw *sql.Tx) error {
		managed = &Transaction{sql: raw, active: true, readOnly: options.ReadOnly}
		return callback(managed)
	})
}

func (tx *Transaction) complete() (err error, firstPanic any) {
	tx.mu.Lock()
	tx.active, tx.sql = false, nil
	callbacks := tx.completion
	tx.completion = nil
	tx.mu.Unlock()
	for _, callback := range callbacks {
		func() {
			defer func() {
				if p := recover(); p != nil && firstPanic == nil {
					firstPanic = p
				}
			}()
			err = combine(err, callback())
		}()
	}
	return
}
