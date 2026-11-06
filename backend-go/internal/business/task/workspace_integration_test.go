//go:build integration

package task_test

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/database"
)

func workspaceFixture(t *testing.T) (context.Context, *task.Service, *database.Pool) {
	t.Helper()
	pool := workspacePool(t)
	store, e := task.NewPostgresStore(pool)
	if e != nil {
		t.Fatal(e)
	}
	svc, e := task.NewService(store)
	if e != nil {
		t.Fatal(e)
	}
	ctx := context.Background()
	if e = pool.WithConn(ctx, func(c *sql.Conn) error {
		_, e := c.ExecContext(ctx, `TRUNCATE project,task,task_tag,chat_session,confirmation,operation_record CASCADE`)
		return e
	}); e != nil {
		t.Fatal(e)
	}
	return ctx, svc, pool
}
func workspaceProject(t *testing.T, ctx context.Context, s *task.Service) task.Project {
	t.Helper()
	name := "功能测试项目"
	p, e := s.SaveProject(ctx, "", task.ProjectChange{Name: &name})
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func workspaceTask(t *testing.T, ctx context.Context, s *task.Service, r task.CreateRequest) task.Task {
	t.Helper()
	if r.Title == "" {
		r.Title = "测试任务"
	}
	v, e := s.Create(ctx, r)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func TestWorkspaceHierarchy(t *testing.T) {
	ctx, s, _ := workspaceFixture(t)
	p := workspaceProject(t, ctx, s)
	other := workspaceProject(t, ctx, s)
	parent := workspaceTask(t, ctx, s, task.CreateRequest{ProjectID: &p.ID})
	child := workspaceTask(t, ctx, s, task.CreateRequest{ProjectID: &p.ID, ParentTaskID: &parent.ID})
	for name, r := range map[string]task.CreateRequest{"cross-project": {Title: "跨项目", ProjectID: &other.ID, ParentTaskID: &parent.ID}, "nested": {Title: "二级", ProjectID: &p.ID, ParentTaskID: &child.ID}} {
		if _, e := s.Create(ctx, r); e == nil {
			t.Fatalf("%s accepted", name)
		}
	}
	if _, e := s.SetCompleted(ctx, parent.ID, true); e == nil {
		t.Fatal("parent with open child completed")
	}
	if e := s.Delete(ctx, parent.ID); e == nil {
		t.Fatal("parent deleted")
	}
	if _, e := s.Update(ctx, parent.ID, task.UpdateRequest{ProjectID: &other.ID}); e == nil {
		t.Fatal("parent moved")
	}
	if _, e := s.SetCompleted(ctx, child.ID, true); e != nil {
		t.Fatal(e)
	}
	if _, e := s.SetCompleted(ctx, parent.ID, true); e != nil {
		t.Fatal(e)
	}
	if _, e := s.SetCompleted(ctx, child.ID, false); e == nil {
		t.Fatal("child reopened below completed parent")
	}
	archived := true
	if _, e := s.SaveProject(ctx, p.ID, task.ProjectChange{Archived: &archived}); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Create(ctx, task.CreateRequest{Title: "归档关联", ProjectID: &p.ID}); e == nil {
		t.Fatal("new archive association accepted")
	}
	if _, e := s.SetCompleted(ctx, parent.ID, false); e != nil {
		t.Fatal(e)
	}
	if _, e := s.SetCompleted(ctx, child.ID, false); e != nil {
		t.Fatal(e)
	}
}
func TestWorkspaceRecurrenceAtomicAndConcurrent(t *testing.T) {
	ctx, s, pool := workspaceFixture(t)
	p := workspaceProject(t, ctx, s)
	due := time.Date(2026, 1, 31, 9, 30, 0, 0, time.FixedZone("CST", 8*3600))
	rule, zone := "MONTHLY", "Asia/Shanghai"
	original := workspaceTask(t, ctx, s, task.CreateRequest{Title: "月末检查", DueAt: &due, ProjectID: &p.ID, RecurrenceType: &rule, RecurrenceTimezone: &zone, Tags: []string{"原标签"}})
	tags := []string{"新标签"}
	done := task.Done
	if _, e := s.Update(ctx, original.ID, task.UpdateRequest{Status: &done, Tags: &tags}); e != nil {
		t.Fatal(e)
	}
	tasks, e := s.ListAll(ctx)
	if e != nil || len(tasks) != 2 {
		t.Fatalf("tasks=%+v err=%v", tasks, e)
	}
	var next task.Task
	for _, v := range tasks {
		if v.ID != original.ID {
			next = v
		}
	}
	want := time.Date(2026, 2, 28, 1, 30, 0, 0, time.UTC)
	if next.DueAt == nil || !next.DueAt.Equal(want) || len(next.Tags) != 1 || next.Tags[0] != "新标签" || next.RecurrenceAnchorDay == nil || *next.RecurrenceAnchorDay != 31 {
		t.Fatalf("next=%+v", next)
	}
	var wg sync.WaitGroup
	errs := make(chan error, 10)
	for range 10 {
		wg.Add(1)
		go func() { defer wg.Done(); _, e := s.SetCompleted(ctx, next.ID, true); errs <- e }()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	if _, e = s.SetCompleted(ctx, original.ID, false); e != nil {
		t.Fatal(e)
	}
	if _, e = s.SetCompleted(ctx, original.ID, true); e != nil {
		t.Fatal(e)
	}
	tasks, e = s.ListAll(ctx)
	if e != nil || len(tasks) != 3 {
		t.Fatalf("duplicate next: n=%d err=%v", len(tasks), e)
	}
	var third task.Task
	for _, v := range tasks {
		if v.Status != task.Done {
			third = v
		}
	}
	if !third.DueAt.Equal(time.Date(2026, 3, 31, 1, 30, 0, 0, time.UTC)) {
		t.Fatalf("lost month anchor: %v", third.DueAt)
	}
	// 完成后生成若失败，原任务完成与生成标记必须同时回滚。
	rule = "DAILY"
	bad := workspaceTask(t, ctx, s, task.CreateRequest{DueAt: &due, RecurrenceType: &rule, RecurrenceTimezone: &zone})
	e = pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		_, e := tx.ExecContext(ctx, `UPDATE task SET status='DONE' WHERE id=$1::uuid`, bad.ID)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, `INSERT INTO task_tag(task_id,tag_index,tag) VALUES($1::uuid,0,NULL)`, bad.ID)
		return e
	})
	if e == nil {
		t.Fatal("expected rollback")
	}
	saved, e := s.Get(ctx, bad.ID)
	if e != nil || saved.Status != task.Todo || saved.RecurrenceGenerated {
		t.Fatalf("partial commit: %+v %v", saved, e)
	}
}
func TestWorkspaceRecurrenceCalendarBoundaries(t *testing.T) {
	ctx, s, _ := workspaceFixture(t)
	for _, tc := range []struct{ rule, zone, from, want string }{
		{"WEEKDAYS", "Asia/Shanghai", "2026-11-06T01:00:00Z", "2026-11-09T01:00:00Z"},
		{"DAILY", "America/New_York", "2026-03-07T14:00:00Z", "2026-03-08T13:00:00Z"},
		{"WEEKLY", "America/New_York", "2026-10-26T13:00:00Z", "2026-11-02T14:00:00Z"},
		{"MONTHLY", "UTC", "2028-01-31T09:00:00Z", "2028-02-29T09:00:00Z"},
	} {
		t.Run(tc.rule+tc.from, func(t *testing.T) {
			due, _ := time.Parse(time.RFC3339, tc.from)
			v := workspaceTask(t, ctx, s, task.CreateRequest{Title: tc.from, DueAt: &due, RecurrenceType: &tc.rule, RecurrenceTimezone: &tc.zone})
			if _, e := s.SetCompleted(ctx, v.ID, true); e != nil {
				t.Fatal(e)
			}
			items, e := s.Search(ctx, task.Query{Keyword: tc.from, Incomplete: true, Size: 100})
			want, _ := time.Parse(time.RFC3339, tc.want)
			if e != nil || len(items.Items) != 1 || !items.Items[0].DueAt.Equal(want) {
				t.Fatalf("got=%+v err=%v", items, e)
			}
		})
	}
}
func TestWorkspaceBackupRoundTrip(t *testing.T) {
	ctx, s, pool := workspaceFixture(t)
	p := workspaceProject(t, ctx, s)
	root := workspaceTask(t, ctx, s, task.CreateRequest{Title: "根任务", ProjectID: &p.ID})
	workspaceTask(t, ctx, s, task.CreateRequest{Title: "子任务", ProjectID: &p.ID, ParentTaskID: &root.ID})
	due := time.Date(2026, 11, 6, 9, 0, 0, 0, time.UTC)
	rule, zone := "DAILY", "Asia/Shanghai"
	recur := workspaceTask(t, ctx, s, task.CreateRequest{DueAt: &due, RecurrenceType: &rule, RecurrenceTimezone: &zone, ProjectID: &p.ID})
	if _, e := s.SetCompleted(ctx, recur.ID, true); e != nil {
		t.Fatal(e)
	}
	archived := true
	if _, e := s.SaveProject(ctx, p.ID, task.ProjectChange{Archived: &archived}); e != nil {
		t.Fatal(e)
	}
	exporter := dataexport.Service{Pool: pool, Name: "test", Version: "1", Contributors: []dataexport.Contributor{task.ExportContributor{}, task.ProjectExportContributor{}}}
	var backup bytes.Buffer
	if e := exporter.Write(ctx, &backup); e != nil {
		t.Fatal(e)
	}
	var payload map[string]any
	if e := json.Unmarshal(backup.Bytes(), &payload); e != nil {
		t.Fatal(e)
	}
	// 人为把子任务放在数组第一项，验证恢复不依赖导出顺序。
	tasks := payload["tasks"].([]any)
	for i, v := range tasks {
		if v.(map[string]any)["parentTaskId"] != nil {
			tasks[0], tasks[i] = tasks[i], tasks[0]
			break
		}
	}
	raw, _ := json.Marshal(payload)
	if e := pool.WithConn(ctx, func(c *sql.Conn) error {
		_, e := c.ExecContext(ctx, `TRUNCATE project,task,task_tag CASCADE`)
		return e
	}); e != nil {
		t.Fatal(e)
	}
	importer := dataimport.Service{Pool: pool, Knowledge: &knowledge.Store{}}
	if _, e := importer.Import(ctx, raw); e != nil {
		t.Fatal(e)
	}
	restored, e := s.Projects(ctx)
	if e != nil || len(restored) != 1 || !restored[0].Archived {
		t.Fatalf("projects=%+v err=%v", restored, e)
	}
	saved, e := s.Get(ctx, recur.ID)
	if e != nil || !saved.RecurrenceGenerated || saved.RecurrenceTimezone != zone {
		t.Fatalf("saved=%+v err=%v", saved, e)
	}
	if _, e = s.SetCompleted(ctx, recur.ID, false); e != nil {
		t.Fatal(e)
	}
	if _, e = s.SetCompleted(ctx, recur.ID, true); e != nil {
		t.Fatal(e)
	}
	all, e := s.ListAll(ctx)
	if e != nil || len(all) != 4 {
		t.Fatalf("restore generated duplicate tasks=%d err=%v", len(all), e)
	}
}
func TestWorkspaceDueFilters(t *testing.T) {
	ctx, s, _ := workspaceFixture(t)
	start := time.Date(2026, 11, 6, 0, 0, 0, 0, time.UTC)
	end := start.AddDate(0, 0, 1)
	for _, offset := range []time.Duration{-time.Microsecond, 0, 12 * time.Hour, 24 * time.Hour} {
		due := start.Add(offset)
		workspaceTask(t, ctx, s, task.CreateRequest{DueAt: &due})
	}
	page, e := s.Search(ctx, task.Query{DueFrom: &start, DueBefore: &end, Incomplete: true, Size: 100})
	if e != nil || page.Total != 2 {
		t.Fatalf("today=%+v %v", page, e)
	}
	old, e := s.Search(ctx, task.Query{DueBefore: &start, Incomplete: true, Size: 100})
	if e != nil || old.Total != 1 {
		t.Fatalf("overdue=%+v %v", old, e)
	}
}

func workspacePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需独立Flyway测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	if opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许临时测试库")
	}
	var err error
	opts.Port, err = strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil {
		t.Fatal(err)
	}
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pool.Close() })
	return pool
}

func TestWorkspaceHTTPAndLatestRuns(t *testing.T) {
	ctx, s, pool := workspaceFixture(t)
	p := workspaceProject(t, ctx, s)
	parent := workspaceTask(t, ctx, s, task.CreateRequest{ProjectID: &p.ID})
	workspaceTask(t, ctx, s, task.CreateRequest{ProjectID: &p.ID, ParentTaskID: &parent.ID})
	err := pool.WithConn(ctx, func(c *sql.Conn) error {
		_, e := c.ExecContext(ctx, `INSERT INTO chat_session(conversation_id,title) VALUES('ws-done','已结束会话'),('ws-open','暂停会话');
 INSERT INTO chat_run(id,conversation_id,request,status,created_at) VALUES
 ('ws-old','ws-done','{}','STOPPED','2026-11-05T00:00:00Z'),('ws-new','ws-done','{}','COMPLETED','2026-11-06T00:00:00Z'),('ws-paused','ws-open','{}','STOPPED','2026-11-06T00:00:00Z')`)
		return e
	})
	if err != nil {
		t.Fatal(err)
	}
	runs, e := s.UnfinishedRuns(ctx)
	if e != nil || runs.Total != 1 || runs.Items[0].ID != "ws-paused" {
		t.Fatalf("runs=%+v err=%v", runs, e)
	}
	handler, e := task.NewHTTP(s, new(idempotency.Service), new(confirmation.Service), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	for _, tc := range []struct {
		method, path, body string
		status             int
		contains           string
	}{
		{"GET", "/api/v1/projects", "", 200, p.Name},
		{"POST", "/api/v1/projects", `{"name":" 新项目 "}`, 201, "新项目"},
		{"GET", "/api/v1/tasks?projectId=" + p.ID, "", 200, "projectId"},
		{"GET", "/api/v1/workspace/runs", "", 200, "暂停会话"},
		{"GET", "/api/v1/tasks?dueFrom=invalid", "", 400, ""},
		{"GET", "/api/v1/tasks?projectId=invalid", "", 400, ""},
		{"POST", "/api/v1/tasks/" + parent.ID + "/complete", "", 400, "请先完成所有子任务"},
		{"POST", "/api/v1/tasks", `{"title":"invalid recurrence","recurrenceType":"DAILY"}`, 400, "截止时间"},
	} {
		r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
		if tc.body != "" {
			r.Header.Set("Content-Type", "application/json")
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.contains) {
			t.Fatalf("%s %s status=%d body=%s", tc.method, tc.path, w.Code, w.Body.String())
		}
	}
}
