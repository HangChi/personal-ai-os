package agent

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"

	"personal-ai-os/backend-go/internal/platform/database"
)

// 严格顺序的 SQL 边界替身：验证查询参数、提交/回滚和故障后的零额外写入。
// 不解释 SQL，不模拟 PostgreSQL 的约束、锁或隔离级别。
type sqlStep struct {
	op, match   string
	rows        [][]driver.Value
	err, rowErr error
	affected    int64
	check       func([]driver.NamedValue)
}
type sqlScript struct {
	t     *testing.T
	mu    sync.Mutex
	steps []sqlStep
	at    int
}

func sqlValues(values ...any) []driver.Value {
	out := make([]driver.Value, len(values))
	for i, v := range values {
		switch n := v.(type) {
		case int:
			out[i] = int64(n)
		default:
			out[i] = v
		}
	}
	return out
}
func queryStep(match string, rows ...[]driver.Value) sqlStep {
	return sqlStep{op: "query", match: match, rows: rows}
}
func execStep(match string) sqlStep  { return sqlStep{op: "exec", match: match, affected: 1} }
func terminalStep(op string) sqlStep { return sqlStep{op: op} }
func (s *sqlScript) next(op, query string, args []driver.NamedValue) (sqlStep, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.at >= len(s.steps) {
		s.t.Errorf("unexpected %s: %s", op, query)
		return sqlStep{}, errors.New("unexpected SQL")
	}
	step := s.steps[s.at]
	s.at++
	if step.op != op || !strings.Contains(query, step.match) {
		s.t.Errorf("step %d: got %s %s, want %s %s", s.at, op, query, step.op, step.match)
		return step, errors.New("SQL contract mismatch")
	}
	if step.check != nil {
		step.check(args)
	}
	return step, step.err
}

type sqlConnector struct{ script *sqlScript }

func (c sqlConnector) Connect(context.Context) (driver.Conn, error) {
	return &sqlConnection{c.script}, nil
}
func (sqlConnector) Driver() driver.Driver { return nil }

type sqlConnection struct{ s *sqlScript }

func (*sqlConnection) Close() error               { return nil }
func (*sqlConnection) Ping(context.Context) error { return nil }
func (c *sqlConnection) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}
func (c *sqlConnection) BeginTx(_ context.Context, _ driver.TxOptions) (driver.Tx, error) {
	_, e := c.s.next("begin", "", nil)
	return &sqlTransaction{c.s}, e
}
func (c *sqlConnection) Prepare(query string) (driver.Stmt, error) {
	_, e := c.s.next("prepare", query, nil)
	return &sqlStatement{c, query}, e
}
func (c *sqlConnection) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if e := ctx.Err(); e != nil {
		return nil, e
	}
	step, e := c.s.next("query", query, args)
	if e != nil {
		return nil, e
	}
	n := 1
	if len(step.rows) > 0 {
		n = len(step.rows[0])
	}
	return &sqlResultRows{rows: step.rows, n: n, err: step.rowErr}, nil
}
func (c *sqlConnection) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if e := ctx.Err(); e != nil {
		return nil, e
	}
	step, e := c.s.next("exec", query, args)
	return driver.RowsAffected(step.affected), e
}

type sqlTransaction struct{ s *sqlScript }

func (x *sqlTransaction) Commit() error   { _, e := x.s.next("commit", "", nil); return e }
func (x *sqlTransaction) Rollback() error { _, e := x.s.next("rollback", "", nil); return e }

type sqlStatement struct {
	c     *sqlConnection
	query string
}

func (*sqlStatement) Close() error  { return nil }
func (*sqlStatement) NumInput() int { return -1 }
func (s *sqlStatement) Exec(args []driver.Value) (driver.Result, error) {
	values := make([]driver.NamedValue, len(args))
	for i, v := range args {
		values[i] = driver.NamedValue{Ordinal: i + 1, Value: v}
	}
	return s.c.ExecContext(context.Background(), s.query, values)
}
func (*sqlStatement) Query([]driver.Value) (driver.Rows, error) {
	return nil, errors.New("statement query is not part of this contract")
}

type sqlResultRows struct {
	rows  [][]driver.Value
	n, at int
	err   error
}

func (r *sqlResultRows) Columns() []string {
	cols := make([]string, r.n)
	for i := range cols {
		cols[i] = fmt.Sprint(i)
	}
	return cols
}
func (*sqlResultRows) Close() error { return nil }
func (r *sqlResultRows) Next(dst []driver.Value) error {
	if r.at >= len(r.rows) {
		if r.err != nil {
			e := r.err
			r.err = nil
			return e
		}
		return io.EOF
	}
	copy(dst, r.rows[r.at])
	r.at++
	return nil
}
func contractPool(t *testing.T, steps ...sqlStep) *database.Pool {
	t.Helper()
	script := &sqlScript{t: t, steps: steps}
	pool, err := database.Open(context.Background(), sqlConnector{script}, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
		script.mu.Lock()
		defer script.mu.Unlock()
		if script.at != len(script.steps) {
			t.Errorf("only consumed %d/%d SQL steps", script.at, len(script.steps))
		}
	})
	return pool
}

func (*sqlConnection) CheckNamedValue(v *driver.NamedValue) error {
	if _, ok := v.Value.([]string); ok {
		return nil
	}
	return driver.ErrSkip
}
