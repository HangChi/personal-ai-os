package chat

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestSessionValidationAndNormalization(t *testing.T) {
	for _, id := range []string{"c-1", "A", strings.Repeat("x", 64)} {
		if err := ValidateConversationID(id); err != nil {
			t.Fatal(err)
		}
	}
	for _, id := range []string{"", "bad id!", "中文", "a_b", strings.Repeat("x", 65)} {
		if err := ValidateConversationID(id); err == nil {
			t.Fatalf("accepted %q", id)
		}
	}
	for _, c := range []struct{ in, want string }{{"\u3000标题\t", "标题"}, {"\u00a0", "\u00a0"}, {"  " + strings.Repeat("x", 190) + "  ", strings.Repeat("x", 190)}} {
		got, err := normalizeTitle(&c.in, false)
		if err != nil || got != c.want {
			t.Fatalf("title=%q err=%v", got, err)
		}
	}
	for _, s := range []string{" ", strings.Repeat("😀", 101), strings.Repeat("x", 201)} {
		if _, err := normalizeTitle(&s, false); err == nil {
			t.Fatal("accepted invalid title")
		}
	}
	if got, err := normalizeTitle(nil, true); err != nil || got != DefaultTitle {
		t.Fatal(got, err)
	}
	if got := (SessionQuery{Page: -1, Size: 1000}).Normalize(); got.Page != 0 || got.Size != 100 {
		t.Fatal(got)
	}
	if got := (SessionQuery{Size: 0}).Normalize(); got.Size != 20 {
		t.Fatal(got)
	}
	if got := likePattern(" \u0130_100%\\ "); got != "%i\u0307\\_100\\%\\\\%" {
		t.Fatalf("pattern=%q", got)
	}
}

// 只实现本组服务测试需要的仓储方法，未声明的方法不允许被静默调用。
type serviceTx struct {
	Tx
	session               Session
	count                 int64
	messages              []Message
	files                 []Attachment
	fail                  error
	saves, reads, deleted int
}

func (tx *serviceTx) Session(_ context.Context, id string, _ bool) (Session, error) {
	tx.reads++
	if id != tx.session.ConversationID {
		return Session{}, notFound(id)
	}
	return tx.session, nil
}
func (tx *serviceTx) SaveSession(_ context.Context, s Session) error {
	tx.session = s
	tx.saves++
	return nil
}
func (tx *serviceTx) CountMessages(context.Context, string) (int64, error) { return tx.count, nil }
func (tx *serviceTx) Messages(context.Context, string, MessageQuery) ([]Message, error) {
	return tx.messages, nil
}
func (tx *serviceTx) AttachmentSnapshots(context.Context, string, *int) ([]Attachment, error) {
	return tx.files, nil
}
func (tx *serviceTx) DeleteMessages(context.Context, string, *int) (int, error) {
	tx.deleted++
	return 2, tx.fail
}
func (tx *serviceTx) DeleteSession(context.Context, string) error { tx.deleted++; return tx.fail }

type serviceStore struct {
	tx        *serviceTx
	commitErr error
}

func (s serviceStore) Transact(ctx context.Context, _ bool, action func(Tx) error) error {
	old := s.tx.session
	if err := action(s.tx); err != nil {
		s.tx.session = old
		return err
	}
	if s.commitErr != nil {
		s.tx.session = old
		return s.commitErr
	}
	return nil
}

func TestSessionWritesKeepActivityAndValidationOrder(t *testing.T) {
	now := time.Date(2026, 11, 4, 0, 0, 0, 0, time.UTC)
	tx := &serviceTx{session: Session{ConversationID: "c-1", Title: "旧标题", CreatedAt: now, UpdatedAt: now}, count: 4}
	svc, err := NewService(serviceStore{tx: tx}, nil)
	if err != nil {
		t.Fatal(err)
	}
	title := "  新标题\u3000"
	got, err := svc.Rename(context.Background(), "c-1", &title)
	if err != nil || got.Title != "新标题" || !got.UpdatedAt.Equal(now) || got.MessageCount != 4 {
		t.Fatal(got, err)
	}
	model := "  custom-not-in-catalog  "
	got, err = svc.SetModel(context.Background(), "c-1", &model)
	if err != nil || got.ChatModel == nil || *got.ChatModel != "custom-not-in-catalog" || !got.UpdatedAt.Equal(now) {
		t.Fatal(got, err)
	}
	got, err = svc.SetModel(context.Background(), "c-1", nil)
	if err != nil || got.ChatModel != nil {
		t.Fatal(got, err)
	}
	reads := tx.reads
	if _, err = svc.Rename(context.Background(), "c-1", nil); err == nil || tx.reads != reads {
		t.Fatal("blank title must fail before read")
	}
	if _, err = svc.Rename(context.Background(), "bad id!", nil); err == nil || err.Error() != "会话标识格式非法" {
		t.Fatal(err)
	}
	model = strings.Repeat("x", 121)
	if _, err = svc.SetModel(context.Background(), "missing", &model); err == nil || !strings.Contains(err.Error(), "会话不存在") {
		t.Fatal("existence precedes model length", err)
	}
}

func TestRewindCleanupAfterCommitOnlyAndSummaryBoundary(t *testing.T) {
	for _, c := range []struct {
		name            string
		fail, commitErr error
		boundary        int
		cleaner         bool
		wantCleanup     bool
		wantBoundary    int
	}{
		{"invalidate", nil, nil, 5, true, true, 0},
		{"keep-summary", nil, nil, 2, true, true, 2},
		{"rollback", errors.New("delete failed"), nil, 5, true, false, 5},
		{"commit-failed", nil, errors.New("commit failed"), 5, true, false, 5},
		{"missing-cleaner", nil, nil, 5, false, false, 5},
	} {
		t.Run(c.name, func(t *testing.T) {
			summary := "旧摘要"
			tx := &serviceTx{session: Session{ConversationID: "c-1", Summary: &summary, SummaryUptoSeq: c.boundary}, files: []Attachment{{ID: ptr("att-1")}}, fail: c.fail}
			cleaned := false
			var clean AttachmentCleaner
			if c.cleaner {
				clean = func(_ context.Context, a []Attachment) {
					if tx.deleted != 1 {
						t.Error("cleanup before deletion")
					}
					cleaned = reflect.DeepEqual(a, tx.files)
				}
			}
			svc, _ := NewService(serviceStore{tx: tx, commitErr: c.commitErr}, clean)
			_, err := svc.Rewind(context.Background(), "c-1", 3)
			if (err != nil) != (c.fail != nil || c.commitErr != nil || !c.cleaner) || cleaned != c.wantCleanup || tx.session.SummaryUptoSeq != c.wantBoundary {
				t.Fatalf("err=%v cleaned=%v summary=%d", err, cleaned, tx.session.SummaryUptoSeq)
			}
		})
	}
}

func TestMessageValidationOrderAndNoLimitCompatibility(t *testing.T) {
	tx := &serviceTx{session: Session{ConversationID: "c-1"}, messages: []Message{}}
	svc, _ := NewService(serviceStore{tx: tx}, nil)
	zero := 0
	if got, err := svc.Messages(context.Background(), "c-1", nil, &zero); err != nil || got == nil {
		t.Fatal(got, err)
	}
	if _, err := svc.Messages(context.Background(), "missing", &zero, &zero); err == nil || !strings.Contains(err.Error(), "会话不存在") {
		t.Fatal(err)
	}
	if _, err := svc.Messages(context.Background(), "c-1", &zero, nil); err == nil || err.Error() != "limit 必须在 1~200 之间" {
		t.Fatal(err)
	}
	if _, err := svc.Rewind(context.Background(), "bad id!", 0); err == nil || err.Error() != "fromSequenceNo 必须为正整数" {
		t.Fatal(err)
	}
}
