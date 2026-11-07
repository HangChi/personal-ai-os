package chat

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"personal-ai-os/backend-go/internal/business/agent"
	"testing"
	"time"
)

// Fail each operation of a successful real-engine run in turn. The original
// transaction implementation still owns all data and successful behavior.
type engineFailTx struct {
	Tx
	seen, fail int
}

func (f *engineFailTx) step() error {
	f.seen++
	if f.seen == f.fail {
		return serviceFailure
	}
	return nil
}
func (f *engineFailTx) Session(c context.Context, id string, lock bool) (Session, error) {
	if e := f.step(); e != nil {
		return Session{}, e
	}
	return f.Tx.Session(c, id, lock)
}
func (f *engineFailTx) SaveSession(c context.Context, s Session) error {
	if e := f.step(); e != nil {
		return e
	}
	return f.Tx.SaveSession(c, s)
}
func (f *engineFailTx) CountMessages(c context.Context, id string) (int64, error) {
	if e := f.step(); e != nil {
		return 0, e
	}
	return f.Tx.CountMessages(c, id)
}
func (f *engineFailTx) Messages(c context.Context, id string, q MessageQuery) ([]Message, error) {
	if e := f.step(); e != nil {
		return nil, e
	}
	return f.Tx.Messages(c, id, q)
}
func (f *engineFailTx) MaxSequence(c context.Context, id string) (int, error) {
	if e := f.step(); e != nil {
		return 0, e
	}
	return f.Tx.MaxSequence(c, id)
}
func (f *engineFailTx) InsertMessage(c context.Context, id string, m NewMessage, n int, at time.Time) error {
	if e := f.step(); e != nil {
		return e
	}
	return f.Tx.InsertMessage(c, id, m, n, at)
}
func (f *engineFailTx) MessageIdentity(c context.Context, id string, n int) (int64, error) {
	if e := f.step(); e != nil {
		return 0, e
	}
	return f.Tx.MessageIdentity(c, id, n)
}
func (f *engineFailTx) InsertCitations(c context.Context, id int64, h []Citation, at time.Time) error {
	if e := f.step(); e != nil {
		return e
	}
	return f.Tx.InsertCitations(c, id, h, at)
}
func TestEngineStorageAndDeliveryFailureBoundaries(t *testing.T) {
	for _, guarded := range []bool{false, true} {
		run := func(t *testing.T, fail int) (int, error) {
			e, tx, tr := engineFixture(t)
			e.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
			if guarded {
				e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
					return RetrievedEvidence{Guarded: true, Answer: "需要核对", Emit: true, Citations: []Citation{{Content: "资料"}}}, nil
				}
			}
			p := prepareEngine(t, e, StreamRequest{Content: "目标", UseKnowledge: engineValue(guarded)})
			f := &engineFailTx{Tx: tx, fail: fail}
			store := oracleStore{f}
			svc, err := NewService(store, nil)
			if err != nil {
				t.Fatal(err)
			}
			e.Service = svc
			e.Assembler.Service = svc
			e.History.Store = store
			events := &engineEvents{}
			err = e.Run(context.Background(), p, "turn", "", nil, events.publish)
			if err != nil && events.has("done") {
				t.Fatal("failed operation published done", events.names)
			}
			if e.Metrics.Active.Load() != 0 {
				t.Fatal("active stream leaked")
			}
			if guarded && len(tr.requests) != 0 {
				t.Fatal("guarded answer invoked model")
			}
			return f.seen, err
		}
		count, err := run(t, 0)
		if err != nil {
			t.Fatal(err)
		}
		for i := 1; i <= count; i++ {
			t.Run(fmt.Sprintf("guarded=%t/op=%d", guarded, i), func(t *testing.T) {
				seen, err := run(t, i)
				if seen < i || (err == nil && !(guarded && i == 12)) {
					t.Fatalf("failure not propagated: %d %v", seen, err)
				}
			})
		}
	}
	for _, name := range []string{"citations", "rag", "delta", "done"} {
		t.Run("publish/"+name, func(t *testing.T) {
			e, _, _ := engineFixture(t)
			e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
				return RetrievedEvidence{Guarded: true, Answer: "核对", Emit: true}, nil
			}
			p := prepareEngine(t, e, StreamRequest{Content: "目标", UseKnowledge: engineValue(true)})
			err := e.Run(context.Background(), p, "turn", "", nil, func(n string, _ any) error {
				if n == name {
					return serviceFailure
				}
				return nil
			})
			if !errors.Is(err, serviceFailure) {
				t.Fatal(err)
			}
		})
	}
	for _, kind := range []string{"attachment", "contributor", "cancel-search", "panic"} {
		t.Run(kind, func(t *testing.T) {
			e, _, _ := engineFixture(t)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			req := StreamRequest{Content: "请联网搜索目标", UseKnowledge: engineValue(true)}
			e.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
				if kind == "retrieve" {
					return RetrievedEvidence{}, serviceFailure
				}
				return RetrievedEvidence{Guarded: kind == "attachment", Answer: "核对"}, nil
			}
			if kind == "attachment" {
				req.Attachments = []Attachment{{ID: ptr("00000000-0000-0000-0000-000000000001")}}
				e.Attachments.Remote = engineBrokenObjectStore{}
			}
			if kind == "contributor" {
				e.Assembler.Contributors = []PromptContributor{{Contribute: func(context.Context, string, string) (string, error) { return "", serviceFailure }}}
			}
			if kind == "cancel-search" {
				e.SearchWeb = func(context.Context, string) ([]agent.WebSource, error) { cancel(); return nil, nil }
			}
			if kind == "panic" {
				e.SearchWeb = func(context.Context, string) ([]agent.WebSource, error) { panic("private") }
			}
			p := prepareEngine(t, e, req)
			if err := e.Run(ctx, p, "turn", "", nil, (&engineEvents{}).publish); err == nil {
				t.Fatal("failure not propagated")
			}
		})
	}
}
func TestEventBufferInvalidPayloadCloseAndWriterFailures(t *testing.T) {
	for _, payload := range []any{make(chan int), map[string]any{"content": 42}} {
		b := NewEventBuffer(func(string, any) error { return nil })
		if err := b.Publish("delta", payload); err == nil {
			t.Fatal("invalid delta accepted")
		}
		_ = b.Close()
	}
	for _, terminal := range []bool{false, true} {
		b := NewEventBuffer(func(string, any) error { return serviceFailure })
		if terminal {
			if err := b.Publish("activity", nil); !errors.Is(err, serviceFailure) {
				t.Fatal(err)
			}
		} else {
			if err := b.Publish("delta", map[string]string{"content": "pending"}); err != nil {
				t.Fatal(err)
			}
		}
		if err := b.Close(); !errors.Is(err, serviceFailure) {
			t.Fatal(err)
		}
	}
	var emitted string
	b := NewEventBuffer(func(_ string, v any) error { emitted = v.(map[string]string)["content"]; return nil })
	if err := b.Publish("delta", map[string]string{"content": "pending"}); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil || emitted != "pending" {
		t.Fatal(emitted, err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
}

type engineBrokenObjectStore struct{ ObjectStore }

func (engineBrokenObjectStore) Read(context.Context, string) ([]byte, bool, error) {
	return nil, false, serviceFailure
}
