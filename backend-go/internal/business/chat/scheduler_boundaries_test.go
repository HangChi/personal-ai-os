package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"strings"
	"testing"
)

func schedulerResponder(dispatch bool, next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
	return func(op, q string, a []driver.NamedValue) (sqlStep, error) {
		if !dispatch && op == "query" && strings.Contains(q, "SELECT id,conversation_id FROM chat_run") {
			return sqlStep{rows: [][]driver.Value{sqlValues("run", "contract")}}, nil
		}
		if dispatch && op == "query" && strings.Contains(q, "SELECT r.id,r.conversation_id,r.status") {
			return sqlStep{rows: [][]driver.Value{sqlValues("run", "contract", "COMPLETED")}}, nil
		}
		return next(op, q, a)
	}
}
func TestSchedulerStorageFailuresReleaseSessionOwnership(t *testing.T) {
	for _, dispatch := range []bool{false, true} {
		type point struct {
			op, q      string
			occurrence int
			rows       bool
		}
		var points []point
		counts := map[string]int{}
		baseline := lifecycleFixture(t, &lifecycleState{status: "RUNNING", pending: true}, func(next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
			base := schedulerResponder(dispatch, next)
			return func(op, q string, a []driver.NamedValue) (sqlStep, error) {
				v, e := base(op, q, a)
				if (op == "query" || op == "exec") && !strings.Contains(q, "UPDATE runtime_lease SET owner=null") {
					key := op + q
					counts[key]++
					points = append(points, point{op, q, counts[key], len(v.rows) > 0})
				}
				return v, e
			}
		})
		invoke := func(r *Runs) error {
			if dispatch {
				return r.Dispatch(context.Background())
			}
			return r.Recover(context.Background())
		}
		if e := invoke(baseline); e != nil {
			t.Fatal(e)
		}
		for i, p := range points {
			for _, mode := range []string{"statement", "rows", "scan"} {
				if mode != "statement" && (p.op != "query" || !p.rows) {
					continue
				}
				t.Run(fmt.Sprintf("%t/%d/%s", dispatch, i, mode), func(t *testing.T) {
					visits := 0
					injected := false
					r := lifecycleFixture(t, &lifecycleState{status: "RUNNING", pending: true}, func(next func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error) {
						base := schedulerResponder(dispatch, next)
						return func(op, q string, a []driver.NamedValue) (sqlStep, error) {
							if op == p.op && q == p.q {
								visits++
								if visits == p.occurrence {
									injected = true
									switch mode {
									case "statement":
										return sqlStep{}, serviceFailure
									case "rows":
										return sqlStep{rowErr: serviceFailure}, nil
									case "scan":
										return sqlStep{rows: [][]driver.Value{sqlValues(nil)}}, nil
									}
								}
							}
							return base(op, q, a)
						}
					})
					e := invoke(r)
					if !injected || e == nil {
						t.Fatal("failure swallowed", injected, e)
					}
					if mode == "statement" && !errors.Is(e, serviceFailure) {
						t.Fatal(e)
					}
					r.Coordinator.Close(context.Background())
				})
			}
		}
	}
}
func TestSchedulerDisabledClosedAndDuplicateStartAreSafe(t *testing.T) {
	r := runsFixture(t)
	r.StartScheduler()
	r.Enabled = true
	if e := r.Close(context.Background()); e != nil {
		t.Fatal(e)
	}
	r.StartScheduler()
	if e := r.launch(context.Background(), "run", PreparedTurn{}, "", nil); e == nil {
		t.Fatal("closed launch")
	}
	var nilLease *runLease
	nilLease.release()
}
