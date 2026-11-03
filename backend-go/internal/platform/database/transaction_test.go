package database

import (
	"context"
	"database/sql"
	"errors"
	"testing"
)

func TestWriteGuardCompletionAndRejection(t *testing.T) {
	for _, reject := range []bool{false, true} {
		pool := openTestPool(t, &testDriver{}, DefaultPoolOptions(true))
		called, body := 0, false
		var saved *Transaction
		failure := errors.New("lost lease")
		ctx := WithWriteGuard(context.Background(), func(ctx context.Context, tx *Transaction) error {
			saved = tx
			if !tx.Writable() {
				t.Fatal("guard needs writable transaction")
			}
			_ = tx.AfterCompletion(func() error {
				called++
				if tx.SQL() != nil || pool.Stats().InUse != 0 {
					t.Error("guard cleanup before connection return")
				}
				return nil
			})
			if reject {
				return failure
			}
			return nil
		})
		err := pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error { body = true; return nil })
		if called != 1 || body == reject || errors.Is(err, failure) != reject || saved.Writable() {
			t.Fatal(called, body, err)
		}
		called = 0
		if err := pool.WithTx(ctx, sql.TxOptions{ReadOnly: true}, func(*sql.Tx) error { return nil }); err != nil || called != 0 {
			t.Fatal("read transaction fenced", err)
		}
	}
}

func TestManagedTransactionCompletionAfterTerminalAndConnectionRelease(t *testing.T) {
	for _, mode := range []string{"commit", "rollback", "panic", "commit failure"} {
		t.Run(mode, func(t *testing.T) {
			state := &testDriver{}
			failure := errors.New("fixture failure")
			if mode == "commit failure" {
				state.commitError = failure
			}
			pool := openTestPool(t, state, DefaultPoolOptions(true))
			var saved *Transaction
			called := 0
			func() {
				defer func() {
					p := recover()
					if mode == "panic" {
						if p != "fixture panic" {
							t.Fatal("panic 必须原样传播")
						}
					} else if p != nil {
						panic(p)
					}
				}()
				err := pool.WithManagedTx(context.Background(), sql.TxOptions{}, func(tx *Transaction) error {
					saved = tx
					if !tx.Writable() || tx.SQL() == nil {
						t.Fatal("回调中必须存在显式写事务")
					}
					if err := tx.AfterCompletion(func() error {
						called++
						if pool.Stats().InUse != 0 || tx.Writable() || tx.SQL() != nil {
							t.Error("完成回调必须在事务结束并归还连接后")
						}
						if len(eventsOf(state, "commit"))+len(eventsOf(state, "rollback")) != 1 {
							t.Error("完成回调不能早于终止")
						}
						return nil
					}); err != nil {
						return err
					}
					if mode == "panic" {
						panic("fixture panic")
					}
					if mode == "rollback" {
						return failure
					}
					return nil
				})
				if (err != nil) != (mode == "rollback" || mode == "commit failure") {
					t.Fatal(err)
				}
			}()
			if called != 1 || saved.AfterCompletion(func() error { return nil }) == nil {
				t.Fatal("完成只调用一次，结束后不得注册")
			}
		})
	}
}

func TestManagedCompletionPanicDoesNotSkipCleanupOrReplaceBusinessPanic(t *testing.T) {
	pool := openTestPool(t, &testDriver{}, DefaultPoolOptions(true))
	for _, businessPanic := range []bool{false, true} {
		called := false
		func() {
			defer func() {
				want := "cleanup panic"
				if businessPanic {
					want = "business panic"
				}
				if recover() != want {
					t.Fatal("错误的 panic 传播")
				}
			}()
			_ = pool.WithManagedTx(context.Background(), sql.TxOptions{}, func(tx *Transaction) error {
				_ = tx.AfterCompletion(func() error { panic("cleanup panic") })
				_ = tx.AfterCompletion(func() error { called = true; return nil })
				if businessPanic {
					panic("business panic")
				}
				return nil
			})
		}()
		if !called {
			t.Fatal("完成回调 panic 不能跳过后续清理")
		}
	}
}

func TestManagedTransactionErrorsAndReadOnly(t *testing.T) {
	pool := openTestPool(t, &testDriver{}, DefaultPoolOptions(true))
	first, second := errors.New("first"), errors.New("second")
	count := 0
	err := pool.WithManagedTx(context.Background(), sql.TxOptions{ReadOnly: true}, func(tx *Transaction) error {
		if tx.Writable() {
			t.Fatal("只读不能用于 fencing")
		}
		if tx.AfterCompletion(nil) == nil {
			t.Fatal("拒绝空完成回调")
		}
		_ = tx.AfterCompletion(func() error { count++; return first })
		_ = tx.AfterCompletion(func() error { count++; return second })
		return nil
	})
	if count != 2 || !errors.Is(err, first) || !errors.Is(err, second) {
		t.Fatal("全部完成回调必须执行且保留错误")
	}
	if pool.WithManagedTx(context.Background(), sql.TxOptions{}, nil) == nil {
		t.Fatal("拒绝空事务回调")
	}
	var zero Transaction
	if zero.Writable() || zero.SQL() != nil || zero.AfterCompletion(func() error { return nil }) == nil {
		t.Fatal("零值不是活动事务")
	}
}
