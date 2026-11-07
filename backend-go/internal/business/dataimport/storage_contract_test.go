package dataimport

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"testing"
	"time"
)

func importFixture() []byte {
	version, content := "1", "导入内容"
	id := UUID("10000000-0000-4000-8000-000000000001")
	at := time.Now().UTC()
	seq, lead := 5, 20
	v := Payload{FormatVersion: &version, Sessions: []*Session{{Session: &SessionMeta{ConversationID: "contract", ReadOnlyReport: true, CreatedAt: &at}, Messages: []*Message{{Role: "user", Content: &content, SequenceNo: &seq}, {Role: "tool", Content: &content}, {Role: "assistant", Content: &content}}}, {Session: &SessionMeta{}}}, Projects: []*Project{{ID: &id, Name: " 项目 ", Archived: true}}, Tasks: []*Task{{ID: &id, Title: "任务", Status: "done", PreviousStatus: "doing", Tags: []string{"", "标签"}, ProjectID: &id, ParentTaskID: &id}, {Title: "跳过"}}, CalendarEvents: []*Calendar{{ID: &id, Title: "日程", StartsAt: &at, Lead: &lead, RecurrenceType: "weekly"}, {}}, Memories: []*Memory{{ID: &id, Content: content, SupersededBy: &id}, {}}}
	raw, _ := json.Marshal(v)
	return raw
}
func importSteps() []sqlStep {
	steps := []sqlStep{terminalStep("begin")}
	for _, table := range guarded {
		steps = append(steps, queryStep("FROM "+table, sqlValues(0)))
	}
	steps = append(steps, terminalStep("commit"), terminalStep("begin"), execStep("LOCK TABLE"))
	for _, table := range guarded {
		steps = append(steps, queryStep("FROM "+table, sqlValues(0)))
	}
	for _, sql := range []string{"INSERT INTO chat_session", "INSERT INTO chat_message", "INSERT INTO chat_message", "UPDATE chat_session", "INSERT INTO project", "INSERT INTO task(", "INSERT INTO task_tag", "UPDATE task SET parent_task_id", "UPDATE project", "INSERT INTO calendar_event", "INSERT INTO memory", "UPDATE memory"} {
		steps = append(steps, execStep(sql))
	}
	return append(steps, terminalStep("commit"))
}
func TestImportWholePayloadTransactionContract(t *testing.T) {
	steps := importSteps()
	s := &Service{Pool: contractPool(t, steps...), Knowledge: &knowledge.Store{}}
	got, e := s.Import(context.Background(), importFixture())
	if e != nil || got.Sessions != 2 || got.Messages != 2 || got.Tasks != 1 || got.CalendarEvents != 1 || got.Memories != 1 || got.Documents != 0 || got.Notes == nil {
		t.Fatal(got, e)
	}
}
func TestImportRejectsOccupiedAndMidWriteRollsBack(t *testing.T) {
	for _, mode := range []string{"occupied", "secondCheck", "write"} {
		t.Run(mode, func(t *testing.T) {
			steps := importSteps()
			failure := errors.New("storage failed")
			if mode == "occupied" {
				steps = steps[:len(guarded)+1]
				steps[1].rows[0][0] = int64(2)
			} else if mode == "secondCheck" {
				end := 2*len(guarded) + 4
				steps = steps[:end]
				steps[len(guarded)+4].rows[0][0] = int64(1)
			} else {
				end := 2*len(guarded) + 7
				steps = steps[:end]
				steps[end-1].err = failure
			}
			steps = append(steps, terminalStep("rollback"))
			s := &Service{Pool: contractPool(t, steps...), Knowledge: &knowledge.Store{}}
			if _, e := s.Import(context.Background(), importFixture()); e == nil {
				t.Fatal("partial import accepted")
			}
		})
	}
}

func TestImportEveryStorageBoundaryRollsBack(t *testing.T) {
	for i, point := range importSteps() {
		if point.op == "begin" || point.op == "commit" {
			continue
		}
		t.Run(fmt.Sprintf("operation-%d", i), func(t *testing.T) {
			steps := importSteps()[:i+1]
			steps[i].err = errors.New("injected import failure")
			steps = append(steps, terminalStep("rollback"))
			svc := &Service{Pool: contractPool(t, steps...), Knowledge: &knowledge.Store{}}
			if _, err := svc.Import(context.Background(), importFixture()); err == nil {
				t.Fatal("partial import accepted")
			}
		})
	}
	for _, payload := range []Payload{{Sessions: []*Session{nil}}, {Sessions: []*Session{{Session: &SessionMeta{ConversationID: "c"}, Messages: []*Message{nil}}}}, {Projects: []*Project{nil}}, {Tasks: []*Task{nil}}, {Memories: []*Memory{nil}}, {CalendarEvents: []*Calendar{nil}}} {
		steps := importSteps()[:2*len(guarded)+4]
		if len(payload.Sessions) > 0 && payload.Sessions[0] != nil {
			steps = append(steps, execStep("INSERT INTO chat_session"))
		}
		steps = append(steps, terminalStep("rollback"))
		version := "1"
		payload.FormatVersion = &version
		raw, _ := json.Marshal(payload)
		svc := &Service{Pool: contractPool(t, steps...), Knowledge: &knowledge.Store{}}
		if _, err := svc.Import(context.Background(), raw); err == nil {
			t.Fatal("invalid import accepted")
		}
	}
}
