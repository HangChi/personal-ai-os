package chat

import (
	"context"
	"database/sql/driver"
	"errors"
	"personal-ai-os/backend-go/internal/business/agent"
	"strings"
	"testing"
	"time"
)

func sessionRow() []driver.Value {
	now := time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC)
	return sqlValues("contract", "标题", now, now, nil, nil, 0, false, 2)
}
func TestPostgresChatTransactionSessionAndIdentityContract(t *testing.T) {
	steps := []sqlStep{terminalStep("begin"), execStep("INSERT INTO chat_session"), queryStep("FOR UPDATE OF s", sessionRow()), execStep("UPDATE chat_session SET title"), queryStep("SELECT count(*) FROM chat_message", sqlValues(2)), queryStep("SELECT coalesce(max(sequence_no),0)", sqlValues(4)), queryStep("sequence_no=$2", sqlValues(9)), queryStep("sequence_no=$2"), queryStep("sequence_no>=", sqlValues(9, "USER", "内容", 4, time.Now().UTC())), terminalStep("commit")}
	s, e := NewPostgresStore(contractPool(t, steps...))
	if e != nil {
		t.Fatal(e)
	}
	e = s.Transact(context.Background(), false, func(tx Tx) error {
		ctx := context.Background()
		if e := tx.InsertSession(ctx, Session{ConversationID: "contract", Title: "标题"}); e != nil {
			return e
		}
		got, e := tx.Session(ctx, "contract", true)
		if e != nil || got.MessageCount != 2 {
			t.Fatal(got, e)
		}
		got.Title = "变更"
		if e = tx.SaveSession(ctx, got); e != nil {
			return e
		}
		if n, e := tx.CountMessages(ctx, "contract"); e != nil || n != 2 {
			t.Fatal(n, e)
		}
		if n, e := tx.MaxSequence(ctx, "contract"); e != nil || n != 4 {
			t.Fatal(n, e)
		}
		if n, e := tx.MessageIdentity(ctx, "contract", 4); e != nil || n != 9 {
			t.Fatal(n, e)
		}
		if n, e := tx.MessageIdentity(ctx, "contract", 5); e != nil || n != 0 {
			t.Fatal(n, e)
		}
		messages, e := tx.RangeMessages(ctx, "contract", 4, 4)
		if e != nil || len(messages) != 1 || messages[0].ID != 9 {
			t.Fatal(messages, e)
		}
		return nil
	})
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Transact(context.Background(), true, nil); e == nil {
		t.Fatal("nil callback accepted")
	}
	if _, e = NewPostgresStore(nil); e == nil {
		t.Fatal("nil pool accepted")
	}
}
func TestPostgresChatSessionsFilteringExportAndOverflow(t *testing.T) {
	for _, mode := range []string{"paged", "export", "overflow", "missing"} {
		t.Run(mode, func(t *testing.T) {
			q := SessionQuery{Keyword: " A_% ", Page: 2, Size: 10}
			steps := []sqlStep{terminalStep("begin")}
			if mode == "missing" {
				steps = append(steps, queryStep("FROM chat_session s WHERE"), terminalStep("rollback"))
			} else {
				count := queryStep("SELECT count(*) FROM chat_session", sqlValues(1))
				count.check = func(a []driver.NamedValue) {
					if a[0].Value != likePattern(q.Keyword) {
						t.Fatal(a)
					}
				}
				steps = append(steps, count)
				if mode == "overflow" {
					q.Page = 1 << 30
					q.Size = 100
				} else {
					match := "ORDER BY s.updated_at DESC LIMIT $2 OFFSET $3"
					if mode == "export" {
						q.All = true
						q.Export = true
						match = "ORDER BY s.conversation_id"
					}
					read := queryStep(match, sessionRow())
					read.check = func(a []driver.NamedValue) {
						if !q.All && (a[1].Value != int64(10) || a[2].Value != int64(20)) {
							t.Fatal(a)
						}
					}
					steps = append(steps, read)
				}
				steps = append(steps, terminalStep("commit"))
			}
			s, _ := NewPostgresStore(contractPool(t, steps...))
			e := s.Transact(context.Background(), true, func(tx Tx) error {
				if mode == "missing" {
					_, e := tx.Session(context.Background(), "contract", false)
					return e
				}
				items, total, e := tx.Sessions(context.Background(), q)
				want := 1
				if mode == "overflow" {
					want = 0
				}
				if e == nil && (len(items) != want || total != 1) {
					t.Fatal(items, total)
				}
				return e
			})
			if (e != nil) != (mode == "missing") {
				t.Fatal(e)
			}
		})
	}
}
func TestPostgresChatMessagesRestoreOrderCitationsAndSnapshots(t *testing.T) {
	now := time.Now().UTC()
	attachment := `[{"id":"fixture","filename":"a.txt","contentType":"text/plain","size":4}]`
	read := queryStep("sequence_no<$2 ORDER BY sequence_no DESC LIMIT $3", sqlValues(2, "ASSISTANT", "回答", 2, now, nil), sqlValues(1, "USER", "问题", 1, now, attachment))
	read.check = func(a []driver.NamedValue) {
		if a[1].Value != int64(3) || a[2].Value != int64(2) {
			t.Fatal(a)
		}
	}
	cite := queryStep("FROM chat_message_citation", sqlValues(2, 1, webCitationDocumentID, "网页", "", 0, "https://fixture.invalid\n网页原文"))
	steps := []sqlStep{terminalStep("begin"), read, cite, queryStep("SELECT attachments FROM chat_message", sqlValues(attachment), sqlValues("broken")), execStep("INSERT INTO chat_message("), execStep("INSERT INTO chat_message_citation"), execStep("DELETE FROM chat_message"), execStep("DELETE FROM chat_session"), terminalStep("commit")}
	s, _ := NewPostgresStore(contractPool(t, steps...))
	e := s.Transact(context.Background(), false, func(tx Tx) error {
		limit, before := 2, 3
		items, e := tx.Messages(context.Background(), "contract", MessageQuery{Limit: &limit, BeforeSeq: &before})
		if e != nil || len(items) != 2 || items[0].ID != 1 || len(items[0].Attachments) != 1 || items[1].Citations[0].URL != "https://fixture.invalid" || items[1].Citations[0].Content != "网页原文" {
			t.Fatal(items, e)
		}
		a, e := tx.AttachmentSnapshots(context.Background(), "contract", &limit)
		if e != nil || len(a) != 1 {
			t.Fatal(a, e)
		}
		if e = tx.InsertMessage(context.Background(), "contract", NewMessage{Role: "USER", Content: "新消息", Attachments: a}, 3, now); e != nil {
			return e
		}
		if e = tx.InsertCitations(context.Background(), 2, items[1].Citations, now); e != nil {
			return e
		}
		if n, e := tx.DeleteMessages(context.Background(), "contract", &limit); e != nil || n != 1 {
			t.Fatal(n, e)
		}
		return tx.DeleteSession(context.Background(), "contract")
	})
	if e != nil {
		t.Fatal(e)
	}
}
func TestPostgresChatStorageFailuresRollback(t *testing.T) {
	for _, operation := range []string{"sessions", "range", "messages", "attachments"} {
		for _, phase := range []string{"query", "scan", "iteration"} {
			t.Run(operation+phase, func(t *testing.T) {
				step := queryStep("SELECT")
				failure := errors.New("storage failed")
				if phase == "query" {
					step.err = failure
				}
				if phase == "scan" {
					step.rows = [][]driver.Value{sqlValues(true, true)}
				}
				if phase == "iteration" {
					step.rowErr = failure
				}
				steps := []sqlStep{terminalStep("begin")}
				if operation == "sessions" {
					steps = append(steps, queryStep("count(*)", sqlValues(1)))
				}
				steps = append(steps, step, terminalStep("rollback"))
				s, _ := NewPostgresStore(contractPool(t, steps...))
				e := s.Transact(context.Background(), true, func(tx Tx) error {
					ctx := context.Background()
					switch operation {
					case "sessions":
						_, _, e := tx.Sessions(ctx, SessionQuery{All: true})
						return e
					case "range":
						_, e := tx.RangeMessages(ctx, "contract", 1, 2)
						return e
					case "messages":
						_, e := tx.Messages(ctx, "contract", MessageQuery{})
						return e
					default:
						_, e := tx.AttachmentSnapshots(ctx, "contract", nil)
						return e
					}
				})
				if e == nil {
					t.Fatal("read error swallowed")
				}
			})
		}
	}
}
func TestUsageCalendarTimezoneAndActualPrecedence(t *testing.T) {
	zone, e := time.LoadLocation("America/New_York")
	if e != nil {
		t.Fatal(e)
	}
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, zone)
	read := queryStep("FROM token_usage WHERE created_at", sqlValues(time.Date(2026, 11, 1, 10, 0, 0, 0, zone), 10, 2, "ACTUAL"), sqlValues(time.Date(2026, 11, 1, 11, 0, 0, 0, zone), 3, 1, "ESTIMATED"), sqlValues(now, 0, 0, "ESTIMATED"))
	read.check = func(a []driver.NamedValue) {
		from, to := a[0].Value.(time.Time), a[1].Value.(time.Time)
		if from.Format("2006-01-02 15:04") != "2026-11-01 00:00" || to.Sub(from) != 49*time.Hour {
			t.Fatal(from, to)
		}
	}
	u := &UsageService{Pool: contractPool(t, read), Zone: zone, Now: func() time.Time { return now }}
	days := 2
	got, e := u.Stats(context.Background(), &days)
	if e != nil || got.Prompt != 13 || got.Completion != 3 || got.Actual != 12 || got.Estimated != 4 || got.Turns != 3 || !got.HasData || len(got.Daily) != 2 || got.Daily[0].Date != "2026-11-01" || got.Daily[0].Estimated || got.Daily[1].Estimated {
		t.Fatal(got, e)
	}
}
func TestUsagePersistLatestAndValidation(t *testing.T) {
	steps := []sqlStep{terminalStep("begin"), execStep("INSERT INTO token_usage"), terminalStep("commit"), terminalStep("begin"), terminalStep("commit"), terminalStep("begin"), terminalStep("rollback"), queryStep("SELECT prompt_tokens", sqlValues(0)), queryStep("SELECT prompt_tokens")}
	u := &UsageService{Pool: contractPool(t, steps...)}
	ctx := context.Background()
	if e := u.Persist(ctx, "contract", UsageRecord{Prompt: 10, Completion: 2, Source: "ACTUAL"}, time.Now()); e != nil {
		t.Fatal(e)
	}
	if e := u.Persist(ctx, "contract", UsageRecord{Source: "ESTIMATED"}, time.Now()); e != nil {
		t.Fatal(e)
	}
	if e := u.Persist(ctx, "contract", UsageRecord{Prompt: -1, Source: "ACTUAL"}, time.Now()); e == nil {
		t.Fatal("negative usage accepted")
	}
	if n, e := u.Latest(ctx, "contract"); e != nil || n == nil || *n != 0 {
		t.Fatal(n, e)
	}
	if n, e := u.Latest(ctx, "contract"); e != nil || n != nil {
		t.Fatal(n, e)
	}
}
func TestInteractionsStoredQuestionsAndSteeringTranscript(t *testing.T) {
	ctx := context.Background()
	steps := []sqlStep{terminalStep("begin"), queryStep("SELECT status FROM chat_run", sqlValues("RUNNING")), queryStep("SELECT id FROM agent_question"), queryStep("FROM agent_question WHERE run_id"), execStep("INSERT INTO agent_question"), terminalStep("commit"), terminalStep("begin"), queryStep("SELECT status FROM chat_run", sqlValues("RUNNING")), queryStep("SELECT id FROM agent_question", sqlValues("question")), queryStep("FROM agent_question WHERE run_id", questionRow("ANSWERED", "甲")), terminalStep("commit"), terminalStep("begin"), queryStep("SELECT status FROM chat_run", sqlValues("RUNNING")), queryStep("FROM agent_followup WHERE", followupRow("STEER", "PENDING", "新增要求")), execStep("UPDATE agent_followup SET status='APPLIED'"), terminalStep("commit"), terminalStep("begin"), queryStep("SELECT question,answer", sqlValues("版本", "甲")), queryStep("FROM agent_followup WHERE", followupRow("STEER", "APPLIED", "新增要求"), followupRow("STEER", "CANCELLED", "取消要求")), terminalStep("commit")}
	s := &Interactions{Pool: contractPool(t, steps...)}
	q, e := s.Ask(ctx, "contract", "run", " 选择版本 ", nil)
	if e != nil || q.ID == "" || q.Status != "PENDING" || q.Options == nil {
		t.Fatal(q, e)
	}
	q, e = s.Ask(ctx, "contract", "run", "选择版本", nil)
	if e != nil || value(q.Answer) != "甲" {
		t.Fatal(q, e)
	}
	inputs, e := s.ApplySteering(ctx, "run")
	if e != nil || len(inputs) != 1 {
		t.Fatal(inputs, e)
	}
	transcript, e := s.Transcript(ctx, "run")
	if e != nil || !strings.Contains(transcript, "用户回答：甲") || !strings.Contains(transcript, "新增要求") || strings.Contains(transcript, "取消") {
		t.Fatal(transcript, e)
	}
}
func TestInteractionsCheckpointAndQuestionStateGuards(t *testing.T) {
	for _, mode := range []string{"continue", "yield", "stopped"} {
		t.Run(mode, func(t *testing.T) {
			state := "RUNNING"
			if mode == "stopped" {
				state = "STOPPED"
			}
			s := &Interactions{Pool: contractPool(t, queryStep("SELECT r.status", sqlValues(state, mode == "yield")))}
			e := s.Checkpoint(context.Background(), "run")
			switch mode {
			case "continue":
				if e != nil {
					t.Fatal(e)
				}
			case "yield":
				if !errors.Is(e, agent.ErrYield) {
					t.Fatal(e)
				}
			case "stopped":
				if !errors.Is(e, context.Canceled) {
					t.Fatal(e)
				}
			}
		})
	}
	for _, mode := range []string{"missing", "stopped", "pending"} {
		t.Run(mode, func(t *testing.T) {
			read := queryStep("SELECT status FROM chat_run", sqlValues("STOPPED"))
			if mode == "missing" {
				read.rows = nil
			}
			steps := []sqlStep{terminalStep("begin"), read}
			if mode == "pending" {
				steps[1].rows = [][]driver.Value{sqlValues("RUNNING")}
				steps = append(steps, queryStep("SELECT id FROM agent_question"), queryStep("FROM agent_question WHERE run_id", questionRow("PENDING", nil)))
			}
			steps = append(steps, terminalStep("rollback"))
			s := &Interactions{Pool: contractPool(t, steps...)}
			if _, e := s.Ask(context.Background(), "contract", "run", "版本", nil); e == nil {
				t.Fatal("unsafe question accepted")
			}
		})
	}
}
