//go:build integration

package workflow

import (
	"bytes"
	"context"
	"database/sql"
	"io"
	"log/slog"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/testdb"
	"strings"
	"sync"
	"testing"
	"time"
)

func ptr[T any](v T) *T { return &v }
func TestReadOnlyWorkflows(t *testing.T) {
	pool := testdb.Open(t)
	ctx := context.Background()
	s := &Service{Pool: pool, Calendar: calendar.NewEvents(pool, config.CalendarProperties{MaxRangeDays: 31, DefaultQueryLimit: 100, MaxQueryLimit: 100}, time.UTC)}
	exec := func(q string, args ...any) {
		t.Helper()
		if e := pool.WithConn(ctx, func(c *sql.Conn) error { _, e := c.ExecContext(ctx, q, args...); return e }); e != nil {
			t.Fatal(e)
		}
	}
	scalar := func(q string, args ...any) string {
		t.Helper()
		var v string
		if e := pool.WithConn(ctx, func(c *sql.Conn) error { return c.QueryRowContext(ctx, q, args...).Scan(&v) }); e != nil {
			t.Fatal(e)
		}
		return v
	}
	exec("TRUNCATE workflow,chat_session,task,calendar_event,knowledge_document CASCADE")
	d, e := s.Save(ctx, "", Change{Name: ptr("晨报"), MaxRunsPerDay: ptr(24)})
	if e != nil || d.Enabled {
		t.Fatal(d, e)
	}
	if e = s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	if scalar("SELECT count(*)::text FROM workflow_execution") != "0" {
		t.Fatal("disabled scheduled")
	}
	// 数据库统一计算DST，缺失时刻向后移，重复时刻采用较晚偏移。
	for _, test := range []struct {
		after, kind string
		weekday     int
		want        string
	}{{"2026-03-08T00:00Z", "MORNING_BRIEF", 1, "2026-03-08 07:30:00+00"}, {"2026-11-01T00:00Z", "MORNING_BRIEF", 1, "2026-11-01 07:30:00+00"}, {"2026-11-02T12:00Z", "WEEKLY_REVIEW", 1, "2026-11-09 07:30:00+00"}} {
		got := scalar("SELECT workflow_next_at('America/New_York','02:30',$1,$2,$3)::text", test.weekday, test.kind, test.after)
		if got != test.want {
			t.Fatal(got, test)
		}
	}
	if scalar("SELECT workflow_next_at('America/New_York','01:30',1,'MORNING_BRIEF','2026-11-01T00:00Z')::text") != "2026-11-01 06:30:00+00" {
		t.Fatal("overlap")
	}
	// 并发手动提交复用同一在途期次。
	var wg sync.WaitGroup
	results := make(chan Execution, 8)
	errs := make(chan error, 8)
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); r, e := s.Run(ctx, d.ID); results <- r; errs <- e }()
	}
	wg.Wait()
	close(results)
	close(errs)
	var id string
	for r := range results {
		if id != "" && id != r.ID {
			t.Fatal("duplicate period")
		}
		id = r.ID
	}
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	exec("INSERT INTO task(title,due_at) VALUES('逾期待办',now()-interval '1 day')")
	if ok, e := s.RunNext(ctx); !ok || e != nil {
		t.Fatal(ok, e)
	}
	r, e := s.Execution(ctx, id)
	if e != nil || r.Status != "SUCCEEDED" || r.ModelCalls != 0 || !strings.Contains(*r.Report, "逾期待办") || len(r.History) != 1 || r.ConversationID == nil {
		t.Fatal(r, e)
	}
	if scalar("SELECT origin FROM chat_run WHERE id=$1", *r.RunID) != "READONLY_WORKFLOW" || scalar("SELECT read_only_report::text FROM chat_session WHERE conversation_id=$1", *r.ConversationID) != "true" {
		t.Fatal("report boundary")
	}
	for _, q := range []string{"INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES($1,'USER','unsafe',2)", "INSERT INTO chat_run(id,conversation_id,request,status) VALUES(gen_random_uuid()::text,$1,'{}','RUNNING')", "UPDATE chat_session SET read_only_report=false WHERE conversation_id=$1"} {
		if e := pool.WithConn(ctx, func(c *sql.Conn) error { _, e := c.ExecContext(ctx, q, *r.ConversationID); return e }); e == nil {
			t.Fatal("readonly bypass", q)
		}
	}
	if scalar("SELECT count(*)::text FROM task") != "1" {
		t.Fatal("business writes")
	}
	for _, kind := range []string{"WEEKLY_REVIEW", "KNOWLEDGE_REVIEW"} {
		v, e := s.Save(ctx, "", Change{Name: ptr(kind), Kind: ptr(kind)})
		if e != nil {
			t.Fatal(e)
		}
		run, e := s.Run(ctx, v.ID)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = s.RunNext(ctx); e != nil {
			t.Fatal(e)
		}
		run, e = s.Execution(ctx, run.ID)
		if e != nil || run.Status != "SUCCEEDED" || run.ModelCalls != 0 {
			t.Fatal(run, e)
		}
	}
	// 输出截断按Unicode字符计数；只读报告不改业务记录。
	exec("INSERT INTO task(title,due_at) SELECT repeat('长',180),now() FROM generate_series(1,10)")
	text, e := s.Report(ctx, Snapshot{Name: "有界报告", Kind: "MORNING_BRIEF", Timezone: "UTC", MaxItems: 8, MaxOutputChars: 1000}, time.Now())
	if e != nil || len([]rune(text)) != 1000 || !strings.Contains(text, "达到输出上限") {
		t.Fatal(text, e)
	}
	text, e = s.Report(ctx, Snapshot{Name: "项数限制", Kind: "MORNING_BRIEF", Timezone: "UTC", MaxItems: 1, MaxOutputChars: 20000}, time.Now())
	if e != nil || strings.Count(text, "• ") != 1 || !strings.Contains(text, "达到数据项上限") {
		t.Fatal(text, e)
	}
	// 失败尝试消耗每日预算；过期token不能提交；单期严格最多3次。
	retry, e := s.Run(ctx, d.ID)
	if e != nil {
		t.Fatal(e)
	}
	token := scalar("SELECT claim_token::text FROM claim_workflow()")
	if scalar("SELECT finish_workflow($1,$2,NULL,'fail')::text", retry.ID, token) != "true" {
		t.Fatal("finish failure")
	}
	exec("UPDATE workflow_execution SET available_at=now()-interval '1 second' WHERE id=$1", retry.ID)
	token2 := scalar("SELECT claim_token::text FROM claim_workflow()")
	if token2 == token || scalar("SELECT finish_workflow($1,$2,'stale',NULL)::text", retry.ID, token) != "false" {
		t.Fatal("lease fence")
	}
	exec("UPDATE workflow_execution SET lease_until=now()-interval '1 second' WHERE id=$1", retry.ID)
	token3 := scalar("SELECT claim_token::text FROM claim_workflow()")
	if token3 == token2 {
		t.Fatal("lease takeover")
	}
	exec("UPDATE workflow_execution SET lease_until=now()-interval '1 second' WHERE id=$1", retry.ID)
	if _, e = s.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	retry, e = s.Execution(ctx, retry.ID)
	if e != nil || retry.Status != "FAILED" || retry.Attempts != 3 || len(retry.History) != 3 {
		t.Fatal(retry, e)
	}
	if _, e = s.Retry(ctx, retry.ID); e == nil {
		t.Fatal("attempt cap")
	}
	quota, e := s.Save(ctx, "", Change{Name: ptr("配额"), MaxRunsPerDay: ptr(1)})
	if e != nil {
		t.Fatal(e)
	}
	first, _ := s.Run(ctx, quota.ID)
	if _, e = s.RunNext(ctx); e != nil {
		t.Fatal(e)
	}
	first, _ = s.Execution(ctx, first.ID)
	if first.Status != "SUCCEEDED" {
		t.Fatal(first)
	}
	second, _ := s.Run(ctx, quota.ID)
	s.RunNext(ctx)
	second, _ = s.Execution(ctx, second.ID)
	if second.Status != "FAILED" || second.Attempts != 0 {
		t.Fatal(second)
	}
	if _, e = s.Retry(ctx, second.ID); e != nil {
		t.Fatal(e)
	}
	s.RunNext(ctx)
	second, _ = s.Execution(ctx, second.ID)
	if second.Status != "FAILED" || second.Attempts != 0 {
		t.Fatal("retry escaped daily quota", second)
	}
	// 启用后补跑一期，下一次在未来；禁用取消尚未领取的自动期次。
	auto, e := s.Save(ctx, "", Change{Name: ptr("自动"), Enabled: ptr(true)})
	if e != nil {
		t.Fatal(e)
	}
	exec("UPDATE workflow SET next_run_at=now()-interval '10 days' WHERE id=$1", auto.ID)
	if e = s.Dispatch(ctx); e != nil {
		t.Fatal(e)
	}
	s.Dispatch(ctx)
	if scalar("SELECT count(*)::text FROM workflow_execution WHERE workflow_id=$1", auto.ID) != "1" || scalar("SELECT (next_run_at>now())::text FROM workflow WHERE id=$1", auto.ID) != "true" {
		t.Fatal("catch up")
	}
	s.Save(ctx, auto.ID, Change{Enabled: ptr(false)})
	s.RunNext(ctx)
	if scalar("SELECT status FROM workflow_execution WHERE workflow_id=$1", auto.ID) != "FAILED" {
		t.Fatal("disable pending")
	}
	if _, e = s.Retry(ctx, "00000000-0000-0000-0000-000000000000"); e == nil {
		t.Fatal("missing retry")
	}
	store, err := chat.NewPostgresStore(pool)
	if err != nil {
		t.Fatal(err)
	}
	chats, err := chat.NewService(store, nil)
	if err != nil {
		t.Fatal(err)
	}
	if e := chats.EnsureWritable(ctx, *r.ConversationID); e == nil {
		t.Fatal("writable report")
	}
	if _, e := chats.Rewind(ctx, *r.ConversationID, 1); e == nil {
		t.Fatal("report rewind")
	}
	engine := &chat.Engine{Service: chats}
	runs := chat.NewRuns(ctx, pool, engine, nil, nil, nil, nil, false)
	if _, e := runs.Start(ctx, *r.ConversationID, "unsafe", chat.StreamRequest{Content: "write"}); e == nil {
		t.Fatal("report start")
	}
	if _, e := engine.Prepare(ctx, *r.ConversationID, chat.StreamRequest{Content: "write"}); e == nil {
		t.Fatal("legacy report stream")
	}
	if _, e = s.History(ctx, d.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = s.List(ctx); e != nil {
		t.Fatal(e)
	}
	scheduler, err := s.Save(ctx, "", Change{Name: ptr("调度器")})
	if err != nil {
		t.Fatal(err)
	}
	scheduledRun, err := s.Run(ctx, scheduler.ID)
	if err != nil {
		t.Fatal(err)
	}
	stop := s.Start(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)))
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		scheduledRun, err = s.Execution(ctx, scheduledRun.ID)
		if err != nil {
			stop()
			t.Fatal(err)
		}
		if scheduledRun.Status == "SUCCEEDED" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	stop()
	if scheduledRun.Status != "SUCCEEDED" {
		t.Fatal("Go scheduler did not complete", scheduledRun)
	}
	var backup bytes.Buffer
	exporter := dataexport.Service{Pool: pool, Name: "fixture", Version: "1", Contributors: []dataexport.Contributor{chat.ExportContributor{}}}
	if e := exporter.Write(ctx, &backup); e != nil {
		t.Fatal(e)
	}
	if !strings.Contains(backup.String(), `"readOnlyReport":true`) {
		t.Fatal("backup lost readonly boundary")
	}
	exec("TRUNCATE workflow,chat_session,task,calendar_event,knowledge_document,knowledge_capture,knowledge_index_job,confirmation,operation_record CASCADE")
	importer := dataimport.Service{Pool: pool, Knowledge: &knowledge.Store{}}
	if _, e := importer.Import(ctx, backup.Bytes()); e != nil {
		t.Fatal(e)
	}
	if e := chats.EnsureWritable(ctx, *r.ConversationID); e == nil {
		t.Fatal("restore lost readonly boundary")
	}

}
