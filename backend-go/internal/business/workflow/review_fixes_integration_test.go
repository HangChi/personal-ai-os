//go:build integration

package workflow

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"fmt"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/testdb"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type countedConnector struct {
	driver.Connector
	queries *atomic.Int64
}
type countedConnection struct {
	driver.Conn
	queries *atomic.Int64
}

func (c countedConnector) Connect(ctx context.Context) (driver.Conn, error) {
	conn, err := c.Connector.Connect(ctx)
	if err != nil {
		return nil, err
	}
	return countedConnection{conn, c.queries}, nil
}
func (c countedConnection) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	c.queries.Add(1)
	return c.Conn.(driver.QueryerContext).QueryContext(ctx, query, args)
}
func (c countedConnection) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	return c.Conn.(driver.ExecerContext).ExecContext(ctx, query, args)
}
func (c countedConnection) BeginTx(ctx context.Context, opts driver.TxOptions) (driver.Tx, error) {
	return c.Conn.(driver.ConnBeginTx).BeginTx(ctx, opts)
}
func (c countedConnection) Ping(ctx context.Context) error { return c.Conn.(driver.Pinger).Ping(ctx) }
func (c countedConnection) CheckNamedValue(value *driver.NamedValue) error {
	return c.Conn.(driver.NamedValueChecker).CheckNamedValue(value)
}

func TestReadOnlyWorkflowsReviewRegressions(t *testing.T) {
	ctx := context.Background()
	var queries atomic.Int64
	pool := testdb.OpenWithConnector(t, func(c driver.Connector) driver.Connector { return countedConnector{c, &queries} })
	s := &Service{Pool: pool, Calendar: calendar.NewEvents(pool, config.CalendarProperties{MaxRangeDays: 31, DefaultQueryLimit: 100, MaxQueryLimit: 100}, time.UTC)}
	exec := func(query string, args ...any) {
		t.Helper()
		if err := pool.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, query, args...); return err }); err != nil {
			t.Fatal(err)
		}
	}
	clean := func() {
		exec("TRUNCATE workflow,chat_session,task,calendar_event,knowledge_document,knowledge_capture CASCADE")
	}
	t.Run("配置编辑不跳过到期期次", func(t *testing.T) {
		clean()
		d, err := s.Save(ctx, "", Change{Name: ptr("到期计划"), Enabled: ptr(true)})
		if err != nil {
			t.Fatal(err)
		}
		exec("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=$1", d.ID)
		d, err = s.Get(ctx, d.ID)
		if err != nil {
			t.Fatal(err)
		}
		due := d.NextRunAt
		for _, edit := range []Change{{Name: ptr("仅改名")}, {MaxRunsPerDay: ptr(12), MaxItems: ptr(10), MaxOutputChars: ptr(2000)}, {Name: ptr("重复保存"), Kind: &d.Kind, Timezone: &d.Timezone, LocalTime: &d.LocalTime, Weekday: &d.Weekday, Enabled: ptr(true)}} {
			got, err := s.Save(ctx, d.ID, edit)
			if err != nil || !got.NextRunAt.Equal(due) {
				t.Fatal("due period moved", got, err)
			}
		}
		if err := s.Dispatch(ctx); err != nil {
			t.Fatal(err)
		}
		if err := s.Dispatch(ctx); err != nil {
			t.Fatal(err)
		}
		page, err := s.History(ctx, d.ID)
		if err != nil {
			t.Fatal(err)
		}
		items := page.(map[string]any)["items"].([]Execution)
		if len(items) != 1 || !items[0].ScheduledFor.Equal(due) {
			t.Fatal("due period was skipped or duplicated", page)
		}
		exec("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=$1", d.ID)
		d, err = s.Get(ctx, d.ID)
		if err != nil {
			t.Fatal(err)
		}
		paused, err := s.Save(ctx, d.ID, Change{Enabled: ptr(false)})
		if err != nil || !paused.NextRunAt.Equal(d.NextRunAt) {
			t.Fatal(paused, err)
		}
		active, err := s.Save(ctx, d.ID, Change{Enabled: ptr(true)})
		if err != nil || !active.NextRunAt.After(time.Now()) {
			t.Fatal(active, err)
		}
		for _, edit := range []Change{{LocalTime: ptr("10:30")}, {Timezone: ptr("UTC")}, {Kind: ptr("WEEKLY_REVIEW")}, {Weekday: ptr(2)}} {
			exec("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=$1", d.ID)
			got, err := s.Save(ctx, d.ID, edit)
			if err != nil || !got.NextRunAt.After(time.Now()) {
				t.Fatal("schedule edit was ignored", got, err)
			}
		}
	})
	t.Run("报告保留优先分区并补足空闲名额", func(t *testing.T) {
		clean()
		exec("INSERT INTO knowledge_document(title,filename,content_type,size_bytes,char_count) SELECT '普通资料'||i,'fixture.txt','text/plain',1,1 FROM generate_series(1,30)i")
		exec("INSERT INTO knowledge_capture(canonical_url,last_error,last_checked_at) VALUES('https://example.com/failed','失败采集样例',now()-interval '10 days')")
		knowledge := Snapshot{Name: "巡检", Kind: "KNOWLEDGE_REVIEW", Timezone: "UTC", MaxItems: 30, MaxOutputChars: 20000}
		report, err := s.Report(ctx, knowledge, time.Now())
		if err != nil || strings.Count(report, "• ") != 30 || strings.Count(report, "• 资料：") != 29 || !strings.Contains(report, "失败采集样例") {
			t.Fatal(report, err)
		}
		exec("INSERT INTO knowledge_capture(canonical_url,pending_job_id,last_checked_at) SELECT 'https://example.com/pending/'||i,gen_random_uuid(),now() FROM generate_series(1,35)i")
		report, err = s.Report(ctx, knowledge, time.Now())
		if err != nil || strings.Count(report, "• 采集：") != 15 || strings.Count(report, "• 资料：") != 15 || !strings.Contains(report, "失败采集样例") {
			t.Fatal(report, err)
		}
		knowledge.MaxItems = 1
		report, err = s.Report(ctx, knowledge, time.Now())
		if err != nil || !strings.Contains(report, "失败采集样例") || strings.Contains(report, "正在索引") || strings.Contains(report, "• 资料：") {
			t.Fatal(report, err)
		}
		day := time.Date(2026, 11, 6, 0, 0, 0, 0, time.UTC)
		exec("INSERT INTO task(title,due_at) SELECT '晨报任务'||i,'2026-11-06T01:00Z'::timestamptz FROM generate_series(1,30)i")
		exec("INSERT INTO task(title,status,completed_at) SELECT '周报任务'||i,'DONE','2026-11-05T01:00Z'::timestamptz FROM generate_series(1,30)i")
		exec("INSERT INTO calendar_event(title,starts_at,ends_at) SELECT '晨报日程'||i,'2026-11-06T02:00Z'::timestamptz,'2026-11-06T03:00Z'::timestamptz FROM generate_series(1,4)i UNION ALL SELECT '周报日程'||i,'2026-11-05T02:00Z'::timestamptz,'2026-11-05T03:00Z'::timestamptz FROM generate_series(1,4)i")
		for _, kind := range []string{"MORNING_BRIEF", "WEEKLY_REVIEW"} {
			d := Snapshot{Name: "报告", Kind: kind, Timezone: "UTC", MaxItems: 4, MaxOutputChars: 20000}
			report, err = s.Report(ctx, d, day)
			if err != nil || strings.Count(report, "• 日程：") != 2 || strings.Count(report, "• ") != 4 {
				t.Fatal(report, err)
			}
			d.MaxItems = 1
			report, err = s.Report(ctx, d, day)
			if err != nil || strings.Count(report, "• 日程：") != 1 || strings.Count(report, "• ") != 1 {
				t.Fatal(report, err)
			}
		}
		exec("TRUNCATE calendar_event CASCADE")
		report, err = s.Report(ctx, Snapshot{Name: "报告", Kind: "MORNING_BRIEF", Timezone: "UTC", MaxItems: 4, MaxOutputChars: 20000}, day)
		if err != nil || strings.Count(report, "• 待办：") != 4 {
			t.Fatal(report, err)
		}
	})
	t.Run("五十期历史固定四次查询且尝试有序", func(t *testing.T) {
		clean()
		d, err := s.Save(ctx, "", Change{Name: ptr("历史查询")})
		if err != nil {
			t.Fatal(err)
		}
		queries.Store(0)
		page, err := s.History(ctx, d.ID)
		if err != nil || queries.Load() != 3 || len(page.(map[string]any)["items"].([]Execution)) != 0 {
			t.Fatal(page, queries.Load(), err)
		}
		exec("INSERT INTO workflow_execution(workflow_id,scheduled_for,source,definition,status,attempts,created_at) SELECT id,'2026-01-01'::timestamptz+i*interval '1 minute','MANUAL',to_jsonb(w),'FAILED',i%4,'2026-01-01'::timestamptz+i*interval '1 minute' FROM workflow w CROSS JOIN generate_series(1,51)i WHERE id=$1", d.ID)
		exec("INSERT INTO workflow_attempt(execution_id,attempt,status,error) SELECT e.id,a,'FAILED',e.id::text||'/'||a FROM workflow_execution e CROSS JOIN LATERAL generate_series(1,e.attempts)a ORDER BY a DESC")
		queries.Store(0)
		page, err = s.History(ctx, d.ID)
		if err != nil || queries.Load() != 4 {
			t.Fatal(page, queries.Load(), err)
		}
		result := page.(map[string]any)
		items := result["items"].([]Execution)
		if result["total"] != 51 || len(items) != 50 {
			t.Fatal("history bound changed", result)
		}
		for _, item := range items {
			if len(item.History) != item.Attempts {
				t.Fatal(item)
			}
			for i, a := range item.History {
				if a.Attempt != i+1 || a.Error == nil || *a.Error != fmt.Sprintf("%s/%d", item.ID, i+1) {
					t.Fatal("attempt mixed or unordered", item)
				}
			}
		}
		queries.Store(0)
		single, err := s.Execution(ctx, items[0].ID)
		if err != nil || queries.Load() != 2 || !reflect.DeepEqual(single.History, items[0].History) {
			t.Fatal(single, queries.Load(), err)
		}
	})
}
