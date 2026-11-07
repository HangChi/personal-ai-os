package chat

import (
	"context"
	"errors"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

type summaryContractTx struct {
	serviceTx
	pending                                    []Message
	identity                                   int64
	sessionErr, rangeErr, identityErr, saveErr error
}

func (tx *summaryContractTx) Session(ctx context.Context, id string, lock bool) (Session, error) {
	if tx.sessionErr != nil {
		return Session{}, tx.sessionErr
	}
	return tx.serviceTx.Session(ctx, id, lock)
}
func (tx *summaryContractTx) RangeMessages(_ context.Context, _ string, from, to int) ([]Message, error) {
	out := []Message{}
	for _, m := range tx.pending {
		if m.SequenceNo >= from && m.SequenceNo <= to {
			out = append(out, m)
		}
	}
	return out, tx.rangeErr
}
func (tx *summaryContractTx) MessageIdentity(context.Context, string, int) (int64, error) {
	return tx.identity, tx.identityErr
}
func (tx *summaryContractTx) SaveSession(ctx context.Context, s Session) error {
	if tx.saveErr != nil {
		return tx.saveErr
	}
	return tx.serviceTx.SaveSession(ctx, s)
}
func summaryContract() (*Summary, *summaryContractTx) {
	tx := &summaryContractTx{serviceTx: serviceTx{session: Session{ConversationID: "c"}}, identity: 3,
		pending: []Message{{ID: 1, SequenceNo: 1, Role: "USER", Content: "第一个需求"}, {ID: 2, SequenceNo: 2, Role: "ASSISTANT", Content: "工具执行结果"}, {ID: 3, SequenceNo: 3, Role: "USER", Content: "补充条件"}}}
	return &Summary{Store: oracleStore{tx}, Properties: config.ContextSummary{Enabled: true, MinMessages: 2, MaxExcerptChars: 200, MaxSummaryChars: 40, Timeout: time.Second}}, tx
}
func TestSummaryRenderUsesPersistedSnapshotAndIgnoresMissingSessions(t *testing.T) {
	s, tx := summaryContract()
	ctx := context.Background()
	if got, e := s.Render(ctx, "missing"); e != nil || got != "" {
		t.Fatal(got, e)
	}
	for _, text := range []string{"", " ", "保留用户决定"} {
		tx.session.Summary = &text
		got, e := s.Render(ctx, "c")
		if e != nil {
			t.Fatal(e)
		}
		if strings.TrimSpace(text) == "" && got != "" {
			t.Fatal(got)
		}
		if strings.TrimSpace(text) != "" && got != "## 早前对话摘要"+string(rune(10))+string(rune(10))+text {
			t.Fatal(got)
		}
	}
	tx.sessionErr = errors.New("read failed")
	if _, e := s.Render(ctx, "c"); !errors.Is(e, tx.sessionErr) {
		t.Fatal(e)
	}
	s.Properties.Enabled = false
	if got, e := s.Render(ctx, "c"); e != nil || got != "" {
		t.Fatal(got, e)
	}
}
func TestSummarySnapshotHonorsProgressMinimumAndExcerptBudget(t *testing.T) {
	s, tx := summaryContract()
	ctx := context.Background()
	boundary := 3
	snapshot, e := s.Snapshot(ctx, "c", &boundary)
	if e != nil || snapshot == nil || snapshot.Boundary != 3 || snapshot.Previous != 0 || snapshot.LastMessageID != 3 || !strings.Contains(snapshot.Transcript, "补充条件") || tx.saves != 0 {
		t.Fatal(snapshot, e)
	}
	s.Properties.MaxExcerptChars = 25
	snapshot, e = s.Snapshot(ctx, "c", &boundary)
	if e != nil || snapshot == nil || snapshot.Boundary != 1 || snapshot.LastMessageID != 1 {
		t.Fatal(snapshot, e)
	}
	tx.session.SummaryUptoSeq = 3
	snapshot, e = s.Snapshot(ctx, "c", &boundary)
	if e != nil || snapshot != nil {
		t.Fatal(snapshot, e)
	}
	tx.session.SummaryUptoSeq = 0
	boundary = 0
	snapshot, e = s.Snapshot(ctx, "c", &boundary)
	if e != nil || snapshot != nil {
		t.Fatal(snapshot, e)
	}
	boundary = 3
	tx.rangeErr = errors.New("range failed")
	if _, e = s.Snapshot(ctx, "c", &boundary); !errors.Is(e, tx.rangeErr) {
		t.Fatal(e)
	}
	tx.rangeErr = nil
	if snapshot, e = s.Snapshot(ctx, "missing", &boundary); e != nil || snapshot != nil {
		t.Fatal(snapshot, e)
	}
	tx.sessionErr = errors.New("session failed")
	if _, e = s.Snapshot(ctx, "c", &boundary); !errors.Is(e, tx.sessionErr) {
		t.Fatal(e)
	}
	s.Properties.Enabled = false
	if snapshot, e = s.Snapshot(ctx, "c", nil); e != nil || snapshot != nil {
		t.Fatal(snapshot, e)
	}
}
func TestSummaryApplyFencesStaleAndRewoundMessages(t *testing.T) {
	for _, name := range []string{"success", "sequence-advanced", "message-replaced", "missing", "read-error", "identity-error", "write-error"} {
		t.Run(name, func(t *testing.T) {
			s, tx := summaryContract()
			snapshot := &SummarySnapshot{ConversationID: "c", Boundary: 3, Previous: 0, LastMessageID: 3}
			var expected error
			switch name {
			case "sequence-advanced":
				tx.session.SummaryUptoSeq = 1
			case "message-replaced":
				tx.identity = 4
			case "missing":
				snapshot.ConversationID = "missing"
			case "read-error":
				expected = errors.New(name)
				tx.sessionErr = expected
			case "identity-error":
				expected = errors.New(name)
				tx.identityErr = expected
			case "write-error":
				expected = errors.New(name)
				tx.saveErr = expected
			}
			applied, e := s.Apply(context.Background(), snapshot, "  "+strings.Repeat("界", 41)+"  ")
			if !errors.Is(e, expected) || applied != (name == "success") {
				t.Fatal(applied, e)
			}
			if name == "success" {
				if tx.saves != 1 || tx.session.SummaryUptoSeq != 3 || value(tx.session.Summary) != strings.Repeat("界", 40) {
					t.Fatal(tx)
				}
			} else if tx.saves != 0 {
				t.Fatal("stale summary was saved")
			}
		})
	}
	s, tx := summaryContract()
	if ok, e := s.Apply(context.Background(), nil, "text"); e != nil || ok {
		t.Fatal(ok, e)
	}
	if ok, e := s.Apply(context.Background(), &SummarySnapshot{}, " "); e != nil || ok || tx.reads != 0 {
		t.Fatal(ok, e)
	}
}
func TestSummaryRefreshComposesHistoryAndPreservesItOnModelFailure(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "failure"}[fail], func(t *testing.T) {
			s, tx := summaryContract()
			old := "此前决定"
			tx.session.Summary = &old
			calls := 0
			upstream := errors.New("synthetic timeout")
			s.Model = func(ctx context.Context, r model.ChatRequest) (model.ChatResult, error) {
				calls++
				deadline, ok := ctx.Deadline()
				if !ok || time.Until(deadline) > time.Second || len(r.Messages) != 2 || !strings.Contains(r.Messages[1].Content, old) || !strings.Contains(r.Messages[1].Content, "补充条件") {
					t.Fatal(r)
				}
				if fail {
					return model.ChatResult{}, upstream
				}
				return model.ChatResult{Content: "  新摘要  "}, nil
			}
			boundary := 3
			ok, e := s.Refresh(context.Background(), "c", &boundary)
			if fail {
				if ok || !errors.Is(e, upstream) || tx.saves != 0 || value(tx.session.Summary) != old {
					t.Fatal(ok, e)
				}
			} else if !ok || e != nil || value(tx.session.Summary) != "新摘要" || tx.saves != 1 {
				t.Fatal(ok, e)
			}
			if calls != 1 {
				t.Fatal(calls)
			}
			if !fail {
				ok, e = s.Refresh(context.Background(), "c", &boundary)
				if ok || e != nil || calls != 1 {
					t.Fatal(ok, e)
				}
			}
			s.Model = nil
			if ok, e = s.Refresh(context.Background(), "c", &boundary); ok || e != nil {
				t.Fatal(ok, e)
			}
		})
	}
}
func TestSummaryBeforeRequestHasBoundedWorkAndMakesNoCallForZeroBoundary(t *testing.T) {
	s, tx := summaryContract()
	calls := 0
	s.Properties.MaxExcerptChars = 25
	tx.identity = 1
	s.Model = func(context.Context, model.ChatRequest) (model.ChatResult, error) {
		calls++
		tx.identity = int64(calls)
		return model.ChatResult{Content: "摘要"}, nil
	}
	s.BeforeRequest(context.Background(), "c", 0)
	if calls != 0 {
		t.Fatal(calls)
	}
	s.BeforeRequest(context.Background(), "c", 3)
	if calls != 2 || tx.session.SummaryUptoSeq != 2 {
		t.Fatal(calls, tx.session)
	}
}
