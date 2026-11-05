//go:build integration

package chat

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"personal-ai-os/backend-go/internal/business/agent"
)

func TestPostgresSummaryOnlyPreservesSafetyAndRestartIntent(t *testing.T) {
	var calls, external atomic.Int32
	var malicious, allowTools atomic.Bool
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var body struct {
			Tools []json.RawMessage `json:"tools"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if !allowTools.Load() && len(body.Tools) != 0 {
			t.Error("summary advertised tools")
		}
		if malicious.Load() {
			emitTool(w, "createTask", `{"title":"forbidden-summary-write"}`)
		} else {
			emitText(w, "仅整理已有结果，原任务仍暂停")
		}
	})
	ctx := context.Background()
	s := f.module.Runs
	f.module.Engine.SearchWeb = func(context.Context, string) ([]agent.WebSource, error) { external.Add(1); return nil, nil }
	f.module.Engine.RetrieveEvidence = func(context.Context, string, int) (RetrievedEvidence, error) {
		external.Add(1)
		return RetrievedEvidence{}, nil
	}
	f.module.Engine.PostTurn = []func(context.Context, string, string, string) error{func(context.Context, string, string, string) error { external.Add(1); return nil }}
	for _, outcome := range []string{"IN_PROGRESS", "FAILED"} {
		c := fixtureSession(t, f.module.Service, "summary "+outcome).ConversationID
		id := "summary-" + outcome
		enabled := true
		content := "搜索网页并整理已有资料"
		content += strings.Repeat("x", 4000-javaLength(content))
		raw, _ := json.Marshal(StreamRequest{Content: content, UseKnowledge: &enabled})
		execute(t, f.pool, "INSERT INTO chat_run(id,conversation_id,request,status) VALUES($1,$2,$3,'INTERRUPTED')", id, c, string(raw))
		execute(t, f.pool, "INSERT INTO operation_record(idempotency_key,source,operation,request_digest,conversation_id,turn_id,outcome,created_at) VALUES($1,'TOOL','createTask',$2,$3,$1,$4,now())", id, strings.Repeat("a", 64), c, outcome)
		if _, e := f.module.Engine.Plans.Create(ctx, c, "original", []string{"first", "second"}, false); e != nil {
			t.Fatal(e)
		}
		before, _ := s.receipts(ctx, id)
		if _, e := s.Resume(ctx, c, id, ""); e == nil {
			t.Fatal("uncertain resume accepted")
		}
		w := httptest.NewRecorder()
		f.module.Handler.ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/chat/sessions/"+c+"/runs/"+id+"/summarize", nil))
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		run := waitRun(t, s, c, id, "INTERRUPTED")
		if run.RecoveryMode != "SUMMARY_ONLY" {
			t.Fatal(run.RecoveryMode)
		}
		hasDone := false
		for _, e := range run.Events {
			if e.Name == "done" {
				hasDone = true
			}
			if e.Name == "activity" {
				t.Fatal("summary invoked tool")
			}
		}
		if !hasDone {
			t.Fatal("summary failed", run.Events)
		}
		after, _ := s.receipts(ctx, id)
		if after != before {
			t.Fatal("receipts changed", after)
		}
		plan, e := f.module.Engine.Plans.Get(ctx, c, true)
		if e != nil || plan == nil || plan.Steps[0].Status != "PENDING" {
			t.Fatal(plan, e)
		}
		if _, e = s.Resume(ctx, c, id, ""); e == nil {
			t.Fatal("summary bypassed uncertainty")
		}
	}
	if external.Load() != 0 {
		t.Fatal("summary called lookup or post-turn hooks", external.Load())
	}

	c := fixtureSession(t, f.module.Service, "malicious-summary").ConversationID
	insertRun(t, f, c, "malicious-summary", "INTERRUPTED")
	malicious.Store(true)
	if _, e := s.Summarize(ctx, c, "malicious-summary"); e != nil {
		t.Fatal(e)
	}
	run := waitRun(t, s, c, "malicious-summary", "INTERRUPTED")
	for _, e := range run.Events {
		if e.Name == "activity" || e.Name == "done" {
			t.Fatal("unsolicited tool response accepted", e)
		}
	}
	if receipt, _ := s.receipts(ctx, run.ID); receipt != "" {
		t.Fatal("tool executed", receipt)
	}
	malicious.Store(false)

	c = fixtureSession(t, f.module.Service, "summary-crash").ConversationID
	insertRun(t, f, c, "summary-crash", "RUNNING")
	execute(t, f.pool, "INSERT INTO chat_run_event(run_id,name,payload) VALUES($1,'summary_only','true')", "summary-crash")
	// A pending steering message must also remain untouched by the scheduler.
	execute(t, f.pool, "INSERT INTO agent_followup(id,run_id,conversation_id,mode,request,status) VALUES($1,$2,$3,'STEER',$4,'PENDING')", "summary-steer", "summary-crash", c, `{"content":"continue"}`)
	before := calls.Load()
	if e := s.Recover(ctx); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, "summary-crash", "INTERRUPTED")
	if calls.Load() != before {
		t.Fatal("restart executed summary as original task")
	}
	// An explicit resume clears the marker, so future ordinary recovery still works.
	allowTools.Store(true)
	if _, e := s.Resume(ctx, c, "summary-crash", ""); e != nil {
		t.Fatal(e)
	}
	waitRun(t, s, c, "summary-crash", "COMPLETED")
}
