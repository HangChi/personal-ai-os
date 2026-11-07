package chat

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

func acquireContractSteps() []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("INSERT INTO runtime_lease"), queryStep("WITH ready AS", sqlValues(0)), terminalStep("commit")}
}
func releaseContractSteps() []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("UPDATE runtime_lease SET owner=null"), terminalStep("commit")}
}

func TestLeaseRenewalLossCannotRestoreTrustAndReleaseIsIdempotent(t *testing.T) {
	for _, mode := range []string{"renewed", "lost-owner", "database-failure"} {
		t.Run(mode, func(t *testing.T) {
			steps := acquireContractSteps()
			var owner string
			steps[2].check = func(a []driver.NamedValue) { owner = a[2].Value.(string) }
			renew := execStep("SET lease_until=clock_timestamp()")
			failure := errors.New("synthetic private lease detail")
			if mode == "lost-owner" {
				renew.affected = 0
			}
			if mode == "database-failure" {
				renew.err = failure
			}
			renew.check = func(a []driver.NamedValue) {
				if a[0].Value != "agent-turn" || a[1].Value != int64(0) || a[2].Value != owner {
					t.Fatal("renewed another owner's lease", a)
				}
			}
			steps = append(steps, renew, execStep("SET lease_until=clock_timestamp()"))
			steps = append(steps, releaseContractSteps()...)
			c, err := coordination.New(contractPool(t, steps...))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := c.Close(context.Background()); err != nil {
					t.Error(err)
				}
			})
			lease, err := c.Acquire(context.Background(), "agent-turn", 1)
			if err != nil || !lease.Valid() {
				t.Fatal(lease, err)
			}
			if strings.Contains(fmt.Sprintf("%v %#v", lease, lease), owner) {
				t.Fatal("lease exposes owner identity")
			}
			err = c.Renew(context.Background())
			if (mode == "database-failure") != errors.Is(err, failure) || lease.Valid() != (mode == "renewed") {
				t.Fatal(lease.Valid(), err)
			}
			if err != nil && (strings.Contains(fmt.Sprintf("%v %#v", err, err), "private") || !strings.Contains(err.Error(), "续租")) {
				t.Fatal("lease failure not sanitized", err)
			}
			if err = c.Renew(context.Background()); err != nil || lease.Valid() != (mode == "renewed") {
				t.Fatal("lost lease trust restored", err)
			}
			if err = lease.Release(context.Background()); err != nil || lease.Valid() {
				t.Fatal(err)
			}
			if err = lease.Release(context.Background()); err != nil {
				t.Fatal("release repeated database write", err)
			}
		})
	}
}

func TestLeaseReleaseAfterCompletionRunsForCommitRollbackAndPanic(t *testing.T) {
	for _, mode := range []string{"commit", "rollback", "panic", "registration-failure"} {
		t.Run(mode, func(t *testing.T) {
			steps := acquireContractSteps()
			if mode != "registration-failure" {
				steps = append(steps, terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)))
				end := "rollback"
				if mode == "commit" {
					end = "commit"
				}
				steps = append(steps, terminalStep(end))
			}
			steps = append(steps, releaseContractSteps()...)
			pool := contractPool(t, steps...)
			c, err := coordination.New(pool)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := c.Close(context.Background()); err != nil {
					t.Error(err)
				}
			})
			lease, err := c.Acquire(context.Background(), "agent-turn", 1)
			if err != nil {
				t.Fatal(err)
			}
			if mode == "registration-failure" {
				if err = lease.ReleaseAfter(nil); err == nil || !lease.Valid() {
					t.Fatal("failed registration invalidated lease", err)
				}
				if err = lease.Release(context.Background()); err != nil {
					t.Fatal(err)
				}
				return
			}
			failure := errors.New("business rollback")
			var panicValue any
			func() {
				defer func() { panicValue = recover() }()
				err = pool.WithManagedTx(context.Background(), sql.TxOptions{}, func(tx *database.Transaction) error {
					if err := lease.Fence(context.Background(), tx); err != nil {
						return err
					}
					if err := lease.ReleaseAfter(tx); err != nil {
						return err
					}
					if lease.Valid() {
						t.Fatal("registered lease can still fence")
					}
					if err := lease.Fence(context.Background(), tx); !errors.Is(err, coordination.ErrLeaseLost) {
						t.Fatal(err)
					}
					if err := lease.ReleaseAfter(tx); err != nil {
						return err
					}
					if err := lease.Release(context.Background()); err != nil {
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
			}()
			if mode == "panic" && panicValue != "fixture panic" || mode == "rollback" && !errors.Is(err, failure) || mode == "commit" && err != nil {
				t.Fatal(panicValue, err)
			}
			if lease.Valid() {
				t.Fatal("lease valid after transaction completion")
			}
		})
	}
}

func TestUsageRecordOutlivesCallerCancellationAndDoesNotRetryFailures(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(fmt.Sprint(fail), func(t *testing.T) {
			at := time.Date(2026, 11, 7, 10, 0, 0, 0, time.FixedZone("fixture", 8*3600))
			write := execStep("INSERT INTO token_usage")
			write.check = func(a []driver.NamedValue) {
				if a[0].Value != "contract" || a[1].Value != "fixture" || a[2].Value != int64(12) || a[3].Value != int64(3) || a[4].Value != "ACTUAL" || !a[5].Value.(time.Time).Equal(at) {
					t.Error(a)
				}
			}
			end := "commit"
			if fail {
				write.err = errors.New("private storage failure")
				end = "rollback"
			}
			logger := slog.New(slog.NewTextHandler(io.Discard, nil))
			u := &UsageService{Pool: contractPool(t, terminalStep("begin"), write, terminalStep(end)), Now: func() time.Time { return at }, Logger: logger}
			executor, err := taskexec.New(taskexec.DefaultOptions(), logger)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { executor.Stop() })
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			u.Record(ctx, nil, "contract", UsageRecord{Prompt: 1})
			u.Record(ctx, executor, "contract", UsageRecord{})
			u.Record(ctx, executor, "contract", UsageRecord{Model: "fixture", Prompt: 12, Completion: 3, Source: "ACTUAL"})
			wait, cancelWait := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancelWait()
			executor.Shutdown()
			if err = executor.Wait(wait); err != nil {
				t.Fatal(err)
			}
			state := executor.Stats()
			if state.Accepted != 1 || state.Completed != 1 || state.Failed != 0 {
				t.Fatal("usage did not remain best effort", state)
			}
		})
	}
}
