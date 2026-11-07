package idempotency

import (
	"context"
	"database/sql/driver"
	"errors"
	"testing"
	"time"
)

func storedOperation() []driver.Value {
	return sqlValues(1, "key", "HTTP", "operation", "digest", "conversation", "turn", "IN_PROGRESS", nil, nil, time.Now().UTC())
}
func TestPostgresIdempotencyClaimsAndFencedCompletionContract(t *testing.T) {
	ctx := context.Background()
	if _, e := NewPostgresStore(nil); e == nil {
		t.Fatal("nil pool")
	}
	for _, mode := range []string{"find", "missing", "claimed", "duplicate", "stale", "not-stale", "complete", "lost", "query-fault", "write-fault"} {
		t.Run(mode, func(t *testing.T) {
			var steps []sqlStep
			switch mode {
			case "find":
				steps = []sqlStep{queryStep("WHERE idempotency_key", storedOperation())}
			case "missing", "query-fault":
				q := queryStep("WHERE idempotency_key")
				if mode == "query-fault" {
					q.err = errors.New("offline")
				}
				steps = []sqlStep{q}
			case "claimed", "duplicate":
				q := execStep("INSERT INTO operation_record")
				if mode == "duplicate" {
					q.affected = 0
				}
				steps = []sqlStep{terminalStep("begin"), q}
				if mode == "duplicate" {
					steps = append(steps, queryStep("WHERE idempotency_key", storedOperation()))
				}
				steps = append(steps, terminalStep("commit"))
			case "stale", "not-stale":
				q := execStep("DELETE FROM operation_record")
				if mode == "not-stale" {
					q.affected = 0
				}
				steps = []sqlStep{q}
			default:
				q := execStep("UPDATE operation_record")
				if mode == "lost" {
					q.affected = 0
				}
				if mode == "write-fault" {
					q.err = errors.New("write")
				}
				steps = []sqlStep{terminalStep("begin"), q}
				if mode == "complete" {
					steps = append(steps, terminalStep("commit"))
				} else {
					steps = append(steps, terminalStep("rollback"))
				}
			}
			s, _ := NewPostgresStore(contractPool(t, steps...))
			switch mode {
			case "find", "missing", "query-fault":
				r, ok, e := s.Find(ctx, "key")
				if (e != nil) != (mode == "query-fault") || ok != (mode == "find") || ok && r.Digest != "digest" {
					t.Fatal(r, ok, e)
				}
			case "claimed", "duplicate":
				r, ok, e := s.Claim(ctx, Record{Key: "key", Digest: "digest"})
				if e != nil || ok != (mode == "claimed") || r.Key != "key" {
					t.Fatal(r, ok, e)
				}
			case "stale", "not-stale":
				ok, e := s.DeleteStaleHTTP(ctx, Record{Key: "key"}, time.Now())
				if e != nil || ok != (mode == "stale") {
					t.Fatal(ok, e)
				}
			default:
				e := s.Complete(ctx, "key", Success, nil, 5)
				if (e == nil) != (mode == "complete") {
					t.Fatal(e)
				}
			}
		})
	}
}
