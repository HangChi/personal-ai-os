package database

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// 受控驱动只验证 database/sql 生命周期与调用顺序，绝不代替 PostgreSQL 集成测试。
type event struct {
	kind       string
	connection int
	options    driver.TxOptions
}

type testDriver struct {
	mu                                                              sync.Mutex
	events                                                          []event
	next                                                            int
	connectError, pingError, beginError, commitError, rollbackError error
	blockConnect                                                    bool
	beginContext                                                    context.Context
}

func (state *testDriver) add(kind string, id int, options driver.TxOptions) {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.events = append(state.events, event{kind, id, options})
}

func (state *testDriver) snapshot() []event {
	state.mu.Lock()
	defer state.mu.Unlock()
	return append([]event(nil), state.events...)
}

type testConnector struct{ state *testDriver }
type unsupportedDriver struct{}

func (unsupportedDriver) Open(string) (driver.Conn, error) {
	return nil, errors.New("必须通过 Connector 创建连接")
}
func (testConnector) Driver() driver.Driver { return unsupportedDriver{} }
func (connector testConnector) Connect(ctx context.Context) (driver.Conn, error) {
	state := connector.state
	if state.blockConnect {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	if state.connectError != nil {
		return nil, state.connectError
	}
	state.mu.Lock()
	state.next++
	id := state.next
	state.events = append(state.events, event{kind: "connect", connection: id})
	state.mu.Unlock()
	return &testConnection{state: state, id: id}, nil
}

type testConnection struct {
	state *testDriver
	id    int
}

func (*testConnection) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("测试只接受 context SQL")
}
func (connection *testConnection) Close() error {
	connection.state.add("close", connection.id, driver.TxOptions{})
	return nil
}
func (connection *testConnection) Begin() (driver.Tx, error) {
	return connection.BeginTx(context.Background(), driver.TxOptions{})
}
func (connection *testConnection) Ping(ctx context.Context) error {
	connection.state.add("ping", connection.id, driver.TxOptions{})
	if err := ctx.Err(); err != nil {
		return err
	}
	return connection.state.pingError
}
func (connection *testConnection) BeginTx(ctx context.Context, options driver.TxOptions) (driver.Tx, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	connection.state.add("begin", connection.id, options)
	connection.state.mu.Lock()
	connection.state.beginContext = ctx
	connection.state.mu.Unlock()
	if connection.state.beginError != nil {
		return nil, connection.state.beginError
	}
	return &testTransaction{connection: connection}, nil
}
func (connection *testConnection) ExecContext(ctx context.Context, _ string, _ []driver.NamedValue) (driver.Result, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	connection.state.add("exec", connection.id, driver.TxOptions{})
	return driver.RowsAffected(1), nil
}

type testTransaction struct{ connection *testConnection }

func (tx *testTransaction) Commit() error {
	tx.connection.state.add("commit", tx.connection.id, driver.TxOptions{})
	return tx.connection.state.commitError
}
func (tx *testTransaction) Rollback() error {
	tx.connection.state.add("rollback", tx.connection.id, driver.TxOptions{})
	return tx.connection.state.rollbackError
}

func openTestPool(t *testing.T, state *testDriver, options PoolOptions) *Pool {
	t.Helper()
	pool, err := Open(context.Background(), testConnector{state}, options)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	})
	return pool
}

func eventsOf(state *testDriver, kind string) []event {
	var events []event
	for _, entry := range state.snapshot() {
		if entry.kind == kind {
			events = append(events, entry)
		}
	}
	return events
}

func TestDefaultPoolSizesMatchJavaRoles(t *testing.T) {
	for _, worker := range []bool{false, true} {
		options := DefaultPoolOptions(worker)
		want := 10
		if worker {
			want = 4
		}
		if options.MaxOpen != want || options.MaxIdle != want || options.ConnectionTimeout != 5*time.Second {
			t.Fatal("池大小或获取超时与 Java 默认值不一致")
		}
		pool := openTestPool(t, &testDriver{}, options)
		if pool.Stats().MaxOpenConnections != want {
			t.Fatal("池上限未应用")
		}
	}
}

func TestOptionsFailBeforeConnecting(t *testing.T) {
	for _, change := range []func(*PoolOptions){
		func(o *PoolOptions) { o.MaxOpen = 0 }, func(o *PoolOptions) { o.MaxIdle = -1 },
		func(o *PoolOptions) { o.MaxIdle = o.MaxOpen + 1 }, func(o *PoolOptions) { o.ConnectionTimeout = 0 },
		func(o *PoolOptions) { o.MaxLifetime = -1 }, func(o *PoolOptions) { o.MaxIdleTime = -1 },
	} {
		options := DefaultPoolOptions(false)
		change(&options)
		state := &testDriver{}
		if pool, err := Open(context.Background(), testConnector{state}, options); err == nil || pool != nil || len(state.snapshot()) != 0 {
			t.Fatal("非法池配置必须在建立连接前失败")
		}
	}
	if _, err := Open(nil, testConnector{&testDriver{}}, DefaultPoolOptions(false)); err == nil {
		t.Fatal("nil context 必须拒绝")
	}
	if _, err := Open(context.Background(), nil, DefaultPoolOptions(false)); err == nil {
		t.Fatal("nil connector 必须拒绝")
	}
}

func TestStartupFailureClosesConnectionsAndSanitizesErrors(t *testing.T) {
	secret := errors.New("fixture-private-password SQL host=/fixture")
	state := &testDriver{pingError: secret}
	pool, err := Open(context.Background(), testConnector{state}, DefaultPoolOptions(false))
	if pool != nil || !errors.Is(err, secret) || len(eventsOf(state, "close")) != 1 {
		t.Fatalf("健康检查失败应关闭池：%v", err)
	}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, err), "fixture-private") {
			t.Fatal("基础设施错误不能回显驱动凭据")
		}
	}
	state = &testDriver{connectError: secret}
	if _, err = Open(context.Background(), testConnector{state}, DefaultPoolOptions(false)); !errors.Is(err, secret) {
		t.Fatal("连接错误原因应可通过 errors.Is 检查")
	}
}

func TestStartupConnectionDeadline(t *testing.T) {
	options := DefaultPoolOptions(false)
	options.ConnectionTimeout = 20 * time.Millisecond
	_, err := Open(context.Background(), testConnector{&testDriver{blockConnect: true}}, options)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("启动连接必须有期限：%v", err)
	}
}

func TestTransactionCommitsOnSameConnectionAndForwardsOptions(t *testing.T) {
	state := &testDriver{}
	pool := openTestPool(t, state, DefaultPoolOptions(false))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	err := pool.WithTx(ctx, sql.TxOptions{Isolation: sql.LevelSerializable, ReadOnly: true}, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "SELECT fixture")
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	begins, execs, commits := eventsOf(state, "begin"), eventsOf(state, "exec"), eventsOf(state, "commit")
	if len(begins) != 1 || len(execs) != 1 || len(commits) != 1 || len(eventsOf(state, "rollback")) != 0 {
		t.Fatal("成功事务必须只提交一次")
	}
	if begins[0].connection != execs[0].connection || begins[0].connection != commits[0].connection {
		t.Fatal("事务语句与提交必须使用同一连接")
	}
	if !begins[0].options.ReadOnly || begins[0].options.Isolation != driver.IsolationLevel(sql.LevelSerializable) {
		t.Fatal("隔离级别/只读标志丢失")
	}
	state.mu.Lock()
	beginCtx := state.beginContext
	state.mu.Unlock()
	if beginCtx != ctx || ctx.Err() != nil {
		t.Fatal("连接获取 context 不能代替事务 context")
	}
	if pool.Stats().InUse != 0 {
		t.Fatal("成功后未归还连接")
	}
}

func TestCallbackErrorAndPanicRollback(t *testing.T) {
	for _, panics := range []bool{false, true} {
		state := &testDriver{}
		pool := openTestPool(t, state, DefaultPoolOptions(false))
		cause := errors.New("fixture business error")
		func() {
			if panics {
				defer func() {
					if recover() != cause {
						t.Error("panic 必须原样传播")
					}
				}()
			}
			err := pool.WithTx(context.Background(), sql.TxOptions{}, func(*sql.Tx) error {
				if panics {
					panic(cause)
				}
				return cause
			})
			if panics {
				t.Error("panic 被吞掉")
			} else if !errors.Is(err, cause) {
				t.Error("业务错误被替换")
			}
		}()
		if len(eventsOf(state, "rollback")) != 1 || len(eventsOf(state, "commit")) != 0 || pool.Stats().InUse != 0 {
			t.Fatal("失败/panic 必须回滚并归还连接")
		}
	}
}

func TestInfrastructureTransactionErrorsNeverRetry(t *testing.T) {
	for _, phase := range []string{"begin", "commit", "rollback"} {
		t.Run(phase, func(t *testing.T) {
			cause := errors.New("fixture-private-driver-error")
			business := errors.New("fixture business failure")
			state := &testDriver{}
			switch phase {
			case "begin":
				state.beginError = cause
			case "commit":
				state.commitError = cause
			case "rollback":
				state.rollbackError = cause
			}
			pool := openTestPool(t, state, DefaultPoolOptions(false))
			calls := 0
			err := pool.WithTx(context.Background(), sql.TxOptions{}, func(*sql.Tx) error {
				calls++
				if phase == "rollback" {
					return business
				}
				return nil
			})
			if !errors.Is(err, cause) || strings.Contains(err.Error(), "fixture-private") {
				t.Fatalf("驱动错误原因应保留但文案脱敏：%v", err)
			}
			wantCalls := 1
			if phase == "begin" {
				wantCalls = 0
			}
			if calls != wantCalls || len(eventsOf(state, "begin")) != 1 || pool.Stats().InUse != 0 {
				t.Fatal("不能自动重试或泄露连接")
			}
			if phase == "rollback" && !errors.Is(err, business) {
				t.Fatal("回滚错误不能吞掉原业务错误")
			}
			if phase == "commit" && len(eventsOf(state, "commit")) != 1 {
				t.Fatal("提交结果不确定时不能重试")
			}
		})
	}
}

func TestConnectionQueueTimeoutDoesNotCancelParent(t *testing.T) {
	options := DefaultPoolOptions(false)
	options.MaxOpen, options.MaxIdle, options.ConnectionTimeout = 1, 1, 20*time.Millisecond
	pool := openTestPool(t, &testDriver{}, options)
	held, release, finished := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		finished <- pool.WithConn(context.Background(), func(*sql.Conn) error { close(held); <-release; return nil })
	}()
	<-held
	defer func() {
		close(release)
		if err := <-finished; err != nil {
			t.Error(err)
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	called := false
	err := pool.WithTx(ctx, sql.TxOptions{}, func(*sql.Tx) error { called = true; return nil })
	if !errors.Is(err, context.DeadlineExceeded) || called || ctx.Err() != nil {
		t.Fatalf("池等待超时不应取消调用方或运行回调：%v", err)
	}
}

func TestNestedCallStartsIndependentTransaction(t *testing.T) {
	state := &testDriver{}
	pool := openTestPool(t, state, DefaultPoolOptions(false))
	innerError := errors.New("fixture inner failure")
	err := pool.WithTx(context.Background(), sql.TxOptions{}, func(*sql.Tx) error {
		inner := pool.WithTx(context.Background(), sql.TxOptions{}, func(*sql.Tx) error { return innerError })
		if !errors.Is(inner, innerError) {
			return errors.New("内部事务错误丢失")
		}
		return nil
	})
	begins := eventsOf(state, "begin")
	if err != nil || len(begins) != 2 || begins[0].connection == begins[1].connection || len(eventsOf(state, "commit")) != 1 || len(eventsOf(state, "rollback")) != 1 {
		t.Fatalf("新事务必须独立，不能隐式加入：%v", err)
	}
}

func TestParentCancellationAndClosedPoolPreventCommit(t *testing.T) {
	state := &testDriver{}
	pool := openTestPool(t, state, DefaultPoolOptions(false))
	ctx, cancel := context.WithCancel(context.Background())
	err := pool.WithTx(ctx, sql.TxOptions{}, func(*sql.Tx) error { cancel(); return nil })
	if !errors.Is(err, context.Canceled) || len(eventsOf(state, "commit")) != 0 {
		t.Fatalf("父请求取消后不能提交：%v", err)
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
	if err := pool.Close(); err != nil {
		t.Fatal("关闭必须幂等")
	}
	err = pool.WithTx(context.Background(), sql.TxOptions{}, func(*sql.Tx) error { t.Error("已关闭的池不能执行回调"); return nil })
	if err == nil {
		t.Fatal("关闭后不能新建事务")
	}
}

func TestNilCallbacksAndCanceledContextDoNotAcquire(t *testing.T) {
	state := &testDriver{}
	pool := openTestPool(t, state, DefaultPoolOptions(false))
	if pool.WithConn(context.Background(), nil) == nil || pool.WithTx(context.Background(), sql.TxOptions{}, nil) == nil {
		t.Fatal("nil 回调应报错")
	}
	if pool.WithConn(nil, func(*sql.Conn) error { return nil }) == nil {
		t.Fatal("nil context 应报错")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if !errors.Is(pool.Ping(ctx), context.Canceled) {
		t.Fatal("健康检查应保留父取消")
	}
	if err := pool.WithTx(ctx, sql.TxOptions{}, func(*sql.Tx) error { t.Error("已取消不能执行回调"); return nil }); !errors.Is(err, context.Canceled) {
		t.Fatal("事务应保留父取消")
	}
	if len(eventsOf(state, "begin")) != 0 {
		t.Fatal("无效调用不能开启事务")
	}
}

func TestConcurrentTransactionsRespectPoolBound(t *testing.T) {
	state := &testDriver{}
	pool := openTestPool(t, state, DefaultPoolOptions(true))
	const workers = 32
	start := make(chan struct{})
	results := make(chan error, workers)
	for range workers {
		go func() {
			<-start
			ctx := context.Background()
			results <- pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
				_, err := tx.ExecContext(ctx, "SELECT fixture")
				return err
			})
		}()
	}
	close(start)
	for range workers {
		if err := <-results; err != nil {
			t.Error(err)
		}
	}
	stats := pool.Stats()
	if len(eventsOf(state, "begin")) != workers || len(eventsOf(state, "commit")) != workers ||
		len(eventsOf(state, "rollback")) != 0 || stats.OpenConnections > 4 || stats.InUse != 0 {
		t.Fatal("并发事务不能超过 Worker 池上限、重复提交或泄露连接")
	}
}
