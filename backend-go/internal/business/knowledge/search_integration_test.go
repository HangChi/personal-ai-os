//go:build integration

package knowledge

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type projectorFunc func(context.Context, string, int64, *Chunk) error

func (f projectorFunc) Project(c context.Context, id string, v int64, ch *Chunk) error {
	return f(c, id, v, ch)
}
func TestP6ProjectionRevisionRecoveryDeleteAndReindex(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	d, e := s.Ingest(ctx, "one.txt", "", []byte("first authoritative document content"))
	if e != nil {
		t.Fatal(e)
	}
	id := scalar[string](t, s.Pool, "SELECT id::text FROM vector_store LIMIT 1")
	p := &Projection{Store: s, Stats: NewStats()}
	calls := 0
	p.Gateway = projectorFunc(func(_ context.Context, got string, v int64, ch *Chunk) error {
		calls++
		if got != id || ch == nil || !strings.Contains(ch.Content, "first") {
			t.Fatal(got, ch)
		}
		execSQL(t, s.Pool, "UPDATE vector_store SET content='changed authoritative content' WHERE id=$1", id)
		return nil
	})
	if _, e = p.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	if n, _ := p.Pending(ctx); n != 1 {
		t.Fatal("old ack removed new revision", n)
	}
	p.Gateway = projectorFunc(func(context.Context, string, int64, *Chunk) error { return errors.New("offline") })
	if _, e = p.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	if scalar[bool](t, s.Pool, "SELECT available_at<=now() FROM knowledge_search_outbox WHERE chunk_id=$1", id) {
		t.Fatal("no retry delay")
	}
	execSQL(t, s.Pool, "UPDATE knowledge_search_outbox SET available_at=now()-interval '1 second'")
	p.Gateway = projectorFunc(func(_ context.Context, _ string, _ int64, ch *Chunk) error {
		if ch == nil || ch.Content != "changed authoritative content" {
			t.Fatal(ch)
		}
		return nil
	})
	if e = p.Sync(ctx); e != nil {
		t.Fatal(e)
	}
	if n, _ := p.Pending(ctx); n != 0 {
		t.Fatal(n)
	}
	if n, e := p.Reindex(ctx); e != nil || n != 1 {
		t.Fatal(n, e)
	}
	if _, e = s.Delete(ctx, d.ID); e != nil {
		t.Fatal(e)
	}
	p.Gateway = projectorFunc(func(_ context.Context, _ string, _ int64, ch *Chunk) error {
		if ch != nil {
			t.Fatal("deleted projected live", ch)
		}
		return nil
	})
	if e = p.Sync(ctx); e != nil {
		t.Fatal(e)
	}
	if n, _ := p.Pending(ctx); n != 0 {
		t.Fatal(n)
	}
	if calls != 1 {
		t.Fatal(calls)
	}
}

type publisherFunc func(context.Context, string, string) error

func (f publisherFunc) Publish(c context.Context, q, id string) error { return f(c, q, id) }
func TestP6SignalConfirmedRevisionFailureAndBusinessIsolation(t *testing.T) {
	s := fixtureStore(t)
	ctx := context.Background()
	j := &Jobs{Store: s}
	job, e := j.Submit(ctx, "", "", "signal.txt", nil, []byte("durable work"))
	if e != nil {
		t.Fatal(e)
	}
	relay := &SignalRelay{Store: s, IndexQueue: "personalos.knowledge.ready", Stats: NewStats()}
	relay.Publisher = publisherFunc(func(_ context.Context, q, id string) error {
		if id != job.ID {
			t.Fatal(id)
		}
		execSQL(t, s.Pool, "UPDATE work_signal_outbox SET revision=nextval('work_signal_revision') WHERE item_id=$1", id)
		return nil
	})
	if e = relay.Relay(ctx); e != nil {
		t.Fatal(e)
	}
	if !scalar[bool](t, s.Pool, "SELECT next_publish<=now() FROM work_signal_outbox WHERE item_id=$1", job.ID) {
		t.Fatal("old confirmation delayed new revision")
	}
	relay.Publisher = publisherFunc(func(context.Context, string, string) error { return errors.New("unconfirmed") })
	if e = relay.Relay(ctx); e == nil {
		t.Fatal("missing confirmation ignored")
	}
	if scalar[string](t, s.Pool, "SELECT status FROM knowledge_index_job WHERE id=$1", job.ID) != "PENDING" {
		t.Fatal("notification consumed business job")
	}
	execSQL(t, s.Pool, "UPDATE work_signal_outbox SET next_publish=now()")
	relay.Publisher = publisherFunc(func(context.Context, string, string) error { return nil })
	if e = relay.Relay(ctx); e != nil {
		t.Fatal(e)
	}
	if scalar[int64](t, s.Pool, "SELECT count(*) FROM work_signal_outbox WHERE item_id=$1", job.ID) != 1 {
		t.Fatal("signal deleted before business completion")
	}
	if scalar[float64](t, s.Pool, "SELECT extract(epoch FROM next_publish-now())::float8 FROM work_signal_outbox WHERE item_id=$1", job.ID) < 28 {
		t.Fatal("missing republish schedule")
	}
}
