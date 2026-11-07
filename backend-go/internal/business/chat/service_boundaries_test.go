package chat

import (
	"context"
	"errors"
	"testing"
	"time"
)

// faultServiceTx rejects a selected transaction operation and records all calls;
// this verifies callers stop before any dependent writes after a storage failure.
type faultServiceTx struct {
	Tx
	fail     string
	calls    []string
	seq      int
	session  Session
	messages []Message
	files    []Attachment
}

var serviceFailure = errors.New("synthetic storage failure")

func (f *faultServiceTx) step(s string) error {
	f.calls = append(f.calls, s)
	if f.fail == s {
		return serviceFailure
	}
	return nil
}
func (f *faultServiceTx) Session(context.Context, string, bool) (Session, error) {
	return f.session, f.step("session")
}
func (f *faultServiceTx) SaveSession(context.Context, Session) error { return f.step("save") }
func (f *faultServiceTx) MaxSequence(context.Context, string) (int, error) {
	return f.seq, f.step("sequence")
}
func (f *faultServiceTx) CountMessages(context.Context, string) (int64, error) {
	return 0, f.step("count")
}
func (f *faultServiceTx) InsertMessage(context.Context, string, NewMessage, int, time.Time) error {
	return f.step("insert")
}
func (f *faultServiceTx) MessageIdentity(context.Context, string, int) (int64, error) {
	return 1, f.step("identity")
}
func (f *faultServiceTx) InsertCitations(context.Context, int64, []Citation, time.Time) error {
	return f.step("citations")
}
func (f *faultServiceTx) Messages(context.Context, string, MessageQuery) ([]Message, error) {
	return f.messages, f.step("messages")
}
func (f *faultServiceTx) Sessions(context.Context, SessionQuery) ([]Session, int64, error) {
	return []Session{f.session}, 1, f.step("sessions")
}
func (f *faultServiceTx) AttachmentSnapshots(context.Context, string, *int) ([]Attachment, error) {
	return f.files, f.step("attachments")
}
func (f *faultServiceTx) DeleteMessages(context.Context, string, *int) (int, error) {
	return 1, f.step("deleteMessages")
}
func (f *faultServiceTx) DeleteSession(context.Context, string) error { return f.step("deleteSession") }
func TestServiceStorageFailuresStopDependentWrites(t *testing.T) {
	ctx := context.Background()
	for _, op := range []struct {
		name  string
		steps []string
		call  func(*Service) error
	}{
		{"rename", []string{"session", "save", "count"}, func(s *Service) error { _, e := s.Rename(ctx, "c-1", ptr("new")); return e }},
		{"model", []string{"session", "save", "count"}, func(s *Service) error { _, e := s.SetModel(ctx, "c-1", nil); return e }},
		{"search", []string{"sessions"}, func(s *Service) error { _, e := s.Search(ctx, SessionQuery{}); return e }},
		{"delete", []string{"session", "attachments", "deleteMessages", "deleteSession"}, func(s *Service) error { return s.Delete(ctx, "c-1") }},
		{"rewind", []string{"session", "attachments", "save", "deleteMessages"}, func(s *Service) error { _, e := s.Rewind(ctx, "c-1", 1); return e }},
		{"title", []string{"session", "count", "save"}, func(s *Service) error { return s.InitializeTitle(ctx, "c-1", ptr("first")) }},
		{"append", []string{"session", "sequence", "insert", "save"}, func(s *Service) error { return s.Append(ctx, "c-1", []NewMessage{{Role: "USER"}}) }},
		{"citations", []string{"session", "messages", "citations"}, func(s *Service) error { return s.AttachCitations(ctx, "c-1", []Citation{{}}) }},
		{"complete", []string{"session", "sequence", "insert", "identity", "citations", "save"}, func(s *Service) error { return s.Complete(ctx, "c-1", "answer", []Citation{{}}) }},
	} {
		for i, step := range op.steps {
			t.Run(op.name+"/"+step, func(t *testing.T) {
				tx := &faultServiceTx{fail: step, session: Session{ConversationID: "c-1", Title: DefaultTitle, SummaryUptoSeq: 1}, messages: []Message{{ID: 1, Role: "ASSISTANT"}}}
				svc, e := NewService(oracleStore{tx: tx}, nil)
				if e != nil {
					t.Fatal(e)
				}
				if e = op.call(svc); !errors.Is(e, serviceFailure) {
					t.Fatal(e)
				}
				if len(tx.calls) != i+1 || tx.calls[i] != step {
					t.Fatal(tx.calls)
				}
			})
		}
	}
}
func TestServiceInvalidIdentifiersOverflowAndEmptySnapshots(t *testing.T) {
	ctx := context.Background()
	tx := &faultServiceTx{session: Session{ConversationID: "c-1"}}
	svc, e := NewService(oracleStore{tx: tx}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if _, e := NewService(nil, nil); e == nil {
		t.Fatal("nil store")
	}
	for _, fn := range []func() error{
		func() error { _, e := svc.Require(ctx, "bad id"); return e },
		func() error { _, e := svc.SetModel(ctx, "bad id", nil); return e },
		func() error { _, e := svc.Messages(ctx, "bad id", nil, nil); return e },
		func() error { return svc.Delete(ctx, "bad id") },
		func() error { _, e := svc.Rewind(ctx, "bad id", 1); return e },
		func() error { return svc.Append(ctx, "bad id", []NewMessage{{}}) },
		func() error { return svc.AttachCitations(ctx, "bad id", []Citation{{}}) },
		func() error { return svc.Complete(ctx, "bad id", "", nil) },
	} {
		if e := fn(); e == nil {
			t.Fatal("invalid id accepted")
		}
	}
	if len(tx.calls) != 0 {
		t.Fatal("validation touched storage", tx.calls)
	}
	tx.seq = 2147483647
	if e := svc.Append(ctx, "c-1", []NewMessage{{}}); e == nil {
		t.Fatal("overflow")
	}
	if e := svc.Complete(ctx, "c-1", "", nil); e == nil {
		t.Fatal("overflow")
	}
	if e := svc.AttachCitations(ctx, "c-1", nil); e != nil {
		t.Fatal(e)
	}
	if e := svc.AttachCitations(ctx, "c-1", []Citation{{}}); e != nil {
		t.Fatal(e)
	}
	tx.files = []Attachment{{}}
	if e := svc.Delete(ctx, "c-1"); e == nil {
		t.Fatal("missing cleaner")
	}
	for _, q := range []SessionQuery{{Export: true, Page: -1, Size: 1}, {Export: true, Size: 0}} {
		if _, e := svc.Search(ctx, q); e == nil {
			t.Fatal(q)
		}
	}
	for _, p := range [][2]int{{-1, 1}, {0, 0}} {
		if _, e := svc.ExportBatch(ctx, p[0], p[1]); e == nil {
			t.Fatal(p)
		}
	}
	if rows, e := svc.ExportBatch(ctx, 0, 1); e != nil || len(rows) != 1 {
		t.Fatal(rows, e)
	}
	missing := &serviceTx{session: Session{ConversationID: "other"}}
	other, e := NewService(oracleStore{tx: missing}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if e := other.InitializeTitle(ctx, "c-1", ptr("first")); e != nil {
		t.Fatal(e)
	}
}
