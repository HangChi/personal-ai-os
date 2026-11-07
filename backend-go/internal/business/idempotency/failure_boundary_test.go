package idempotency

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

type failureStore struct {
	*memoryStore
	findErr, claimErr, deleteErr, completeErr error
	staleRace, claimRace                      bool
}

func (s *failureStore) Find(c context.Context, k string) (Record, bool, error) {
	if s.findErr != nil {
		return Record{}, false, s.findErr
	}
	return s.memoryStore.Find(c, k)
}
func (s *failureStore) Claim(c context.Context, r Record) (Record, bool, error) {
	if s.claimErr != nil {
		return Record{}, false, s.claimErr
	}
	if s.claimRace {
		r.Digest = SHA256("other")
		return r, false, nil
	}
	return s.memoryStore.Claim(c, r)
}
func (s *failureStore) DeleteStaleHTTP(c context.Context, r Record, at time.Time) (bool, error) {
	if s.deleteErr != nil {
		return false, s.deleteErr
	}
	if s.staleRace {
		s.records[r.Key] = Record{Key: r.Key, Digest: r.Digest, Outcome: Success}
		return false, nil
	}
	return s.memoryStore.DeleteStaleHTTP(c, r, at)
}
func (s *failureStore) Complete(c context.Context, k string, o Outcome, p *string, d int) error {
	if s.completeErr != nil {
		return s.completeErr
	}
	return s.memoryStore.Complete(c, k, o, p, d)
}
func TestIdempotencyFailureBoundariesKeepUncertainActionsSingleUse(t *testing.T) {
	if _, e := NewService(nil); e == nil {
		t.Fatal("nil store")
	}
	for _, mode := range []string{"find", "claim", "stale-delete", "stale-race", "claim-race", "complete", "invalid-outcome"} {
		t.Run(mode, func(t *testing.T) {
			f := errors.New("storage fault")
			s := &failureStore{memoryStore: newMemoryStore()}
			calls := 0
			switch mode {
			case "find":
				s.findErr = f
			case "claim":
				s.claimErr = f
			case "stale-delete", "stale-race":
				s.records["key"] = Record{Key: "key", Digest: SHA256("same"), Source: SourceHTTP, Outcome: InProgress, CreatedAt: time.Now().Add(-2 * StaleAfter)}
				s.staleRace = mode == "stale-race"
				if !s.staleRace {
					s.deleteErr = f
				}
			case "claim-race":
				s.claimRace = true
			case "complete":
				s.completeErr = f
			}
			service, _ := NewService(s)
			r, e := service.Execute(context.Background(), request("key", SourceHTTP, SHA256("same")), func(context.Context) (Result, error) {
				calls++
				if mode == "invalid-outcome" {
					return Result{Outcome: InProgress}, nil
				}
				return NewResult(nil, Success), nil
			})
			if mode == "stale-race" {
				if e != nil || !r.Replayed || calls != 0 {
					t.Fatal(r, e, calls)
				}
				return
			}
			if e == nil {
				t.Fatal("failure swallowed")
			}
			if mode != "complete" && mode != "invalid-outcome" && calls != 0 {
				t.Fatal("action ran after failed storage")
			}
			if mode == "complete" && (!strings.Contains(e.Error(), "结果不明") || calls != 1) {
				t.Fatal(e, calls)
			}
			if mode == "invalid-outcome" && s.records["key"].Outcome != Failed {
				t.Fatal("invalid receipt not failed")
			}
		})
	}
	service, _ := NewService(newMemoryStore())
	good := request("key", SourceHTTP, SHA256("same"))
	for _, r := range []Request{{}, {Key: "key", Source: "bad", Operation: "op", Digest: good.Digest}, {Key: "key", Source: SourceHTTP, Operation: "op", Digest: strings.Repeat("z", 64)}} {
		if _, e := service.Execute(context.Background(), r, func(context.Context) (Result, error) { t.Fatal("invalid request executed"); return Result{}, nil }); e == nil {
			t.Fatal("invalid request accepted")
		}
	}
	if _, e := service.Execute(context.Background(), good, nil); e == nil {
		t.Fatal("nil action")
	}
	if _, e := Digest(make(chan int)); e == nil {
		t.Fatal("unsupported canonical JSON")
	}
	if (&ConflictError{Kind: ErrPreviousFailed}).Error() == "" {
		t.Fatal("missing diagnostic")
	}
	for _, q := range []sqlStep{execStep("DELETE FROM operation_record"), execStep("INSERT INTO operation_record")} {
		q.err = errors.New("write")
		steps := []sqlStep{q}
		if strings.Contains(q.match, "INSERT") {
			steps = []sqlStep{terminalStep("begin"), q, terminalStep("rollback")}
		}
		store, _ := NewPostgresStore(contractPool(t, steps...))
		var e error
		if strings.Contains(q.match, "DELETE") {
			_, e = store.DeleteStaleHTTP(context.Background(), Record{Key: "k"}, time.Now())
		} else {
			_, _, e = store.Claim(context.Background(), Record{Key: "k"})
		}
		if e == nil {
			t.Fatal("SQL failure swallowed")
		}
	}
}
