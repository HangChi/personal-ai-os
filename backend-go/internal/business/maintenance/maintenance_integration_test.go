//go:build integration

package maintenance

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/testdb"
	"strings"
	"testing"
	"time"
)

func TestMaintenanceCenter(t *testing.T) {
	pool := testdb.Open(t)
	ctx := context.Background()
	s, e := NewService(pool, config.Index)
	if e != nil {
		t.Fatal(e)
	}
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
	exec("TRUNCATE maintenance_check,runtime_heartbeat")
	before := scalar("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM task t")
	raw, e := os.ReadFile("../../../../specs/035-safe-automation/fixtures/maintenance.json")
	if e != nil {
		t.Fatal(e)
	}
	var cases []struct {
		Name    string          `json:"name"`
		Payload json.RawMessage `json:"payload"`
		Status  string          `json:"status"`
		Issue   *string         `json:"issue"`
	}
	if e = json.Unmarshal(raw, &cases); e != nil {
		t.Fatal(e)
	}
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			v, e := s.Verify(ctx, c.Payload)
			if e != nil {
				t.Fatal(e)
			}
			if v["status"] != c.Status {
				t.Fatal(v)
			}
			issues, _ := json.Marshal(v["issues"])
			if c.Issue != nil && !strings.Contains(string(issues), *c.Issue) {
				t.Fatal(v)
			}
			digest := sha256.Sum256(c.Payload)
			if v["digest"] != hex.EncodeToString(digest[:]) {
				t.Fatal("digest changed")
			}
		})
	}
	for _, bad := range [][]byte{[]byte("broken"), {0xff}, []byte(`{"x":"\u0000"}`), []byte(`[]`), {0}} {
		v, e := s.Verify(ctx, bad)
		if e != nil || v["status"] != "INVALID" {
			t.Fatal(v, e)
		}
	}
	invalidRaw, e := os.ReadFile("../../../../specs/035-safe-automation/fixtures/maintenance-invalid-json.json")
	if e != nil {
		t.Fatal(e)
	}
	var invalidJSON []struct{ Name, Content string }
	if e = json.Unmarshal(invalidRaw, &invalidJSON); e != nil {
		t.Fatal(e)
	}
	for _, fixture := range invalidJSON {
		t.Run(fixture.Name, func(t *testing.T) {
			v, e := s.Verify(ctx, []byte(fixture.Content))
			if e != nil || v["status"] != "INVALID" {
				t.Fatal(v, e)
			}
			digest := sha256.Sum256([]byte(fixture.Content))
			if v["digest"] != hex.EncodeToString(digest[:]) {
				t.Fatal("digest changed")
			}
			if _, e := s.Drill(ctx, []byte(fixture.Content)); e == nil || !strings.Contains(e.Error(), "外部报告") {
				t.Fatal("invalid drill was not rejected as validation", e)
			}
		})
	}
	if _, e = s.Verify(ctx, make([]byte, MaxBytes+1)); e == nil {
		t.Fatal("oversized backup")
	}
	// 错误记录最多100条；每类最多10000条，避免遍历恶意无限集合。
	var payload map[string]any
	json.Unmarshal(cases[0].Payload, &payload)
	invalids := make([]any, 101)
	for i := range invalids {
		invalids[i] = map[string]any{}
	}
	payload["tasks"] = invalids
	b, _ := json.Marshal(payload)
	v, e := s.Verify(ctx, b)
	if e != nil || v["truncated"] != true || len(v["issues"].([]any)) != 100 {
		t.Fatal(v, e)
	}
	payload["tasks"] = make([]any, 10001)
	b, _ = json.Marshal(payload)
	if v, e = s.Verify(ctx, b); e != nil || v["status"] != "INVALID" {
		t.Fatal(v, e)
	}
	if before != scalar("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM task t") {
		t.Fatal("verification modified business data")
	}
	// 纯规则函数在READ ONLY事务中也能运行，不依赖导入/模型。
	if e = pool.WithTx(ctx, sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error {
		var result string
		return tx.QueryRowContext(ctx, "SELECT maintenance_verify_text($1)::text", string(cases[0].Payload)).Scan(&result)
	}); e != nil {
		t.Fatal(e)
	}
	report := []byte(`{"tool":"scripts/dev/backup.py drill","summary":"独立库演练的外部结果","outcome":"PASSED","completedAt":"2026-11-06T00:00:00Z"}`)
	v, e = s.Drill(ctx, report)
	if e != nil || v["status"] != "UNVERIFIED" || v["source"] != "EXTERNAL" || v["independentVerification"] != false {
		t.Fatal(v, e)
	}
	for _, bad := range [][]byte{[]byte(`{}`), []byte(`{"tool":1}`), []byte(`null`), make([]byte, 16385)} {
		if _, e = s.Drill(ctx, bad); e == nil {
			t.Fatal("invalid drill accepted")
		}
	}
	// 真实定时循环续写，同一实例不重置started_at；停止后不再写心跳。
	stop := s.start(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)), 20*time.Millisecond)
	t.Cleanup(stop)
	deadline := time.Now().Add(2 * time.Second)
	for scalar("SELECT count(*)::text FROM runtime_heartbeat WHERE backend='GO'") == "0" && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	first := scalar("SELECT last_seen_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID)
	started := scalar("SELECT started_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID)
	for scalar("SELECT last_seen_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID) == first && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	stop()
	after := scalar("SELECT last_seen_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID)
	if first == after || started != scalar("SELECT started_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID) {
		t.Fatal("not a genuine heartbeat")
	}
	time.Sleep(35 * time.Millisecond)
	if after != scalar("SELECT last_seen_at::text FROM runtime_heartbeat WHERE instance_id=$1", s.instanceID) {
		t.Fatal("heartbeat continued after stop")
	}
	exec("UPDATE runtime_heartbeat SET last_seen_at=now()-interval '5 minutes' WHERE instance_id=$1", s.instanceID)
	snapshot, e := s.Snapshot(ctx)
	if e != nil {
		t.Fatal(e)
	}
	heartbeats := snapshot["heartbeats"].(map[string]any)["items"].([]any)
	if heartbeats[0].(map[string]any)["status"] != "STALE" {
		t.Fatal(snapshot)
	}
	exec("INSERT INTO knowledge_index_job(id,document_id,replacing,filename,digest,status,attempts) SELECT gen_random_uuid(),gen_random_uuid(),false,'维护失败样例.txt',repeat('a',64),'FAILED',3 FROM generate_series(1,25)")
	snapshot, e = s.Snapshot(ctx)
	if e != nil {
		t.Fatal(e)
	}
	failed := snapshot["failedIndexJobs"].(map[string]any)
	if failed["total"] != float64(25) || len(failed["items"].([]any)) != 20 {
		t.Fatal("queue not bounded", failed)
	}
	exec("DELETE FROM knowledge_index_job WHERE status='FAILED' AND id NOT IN (SELECT id FROM knowledge_index_job WHERE status='FAILED' LIMIT 1)")
	exec("INSERT INTO reminder(id,source_type,source_id,title,occurrence_at,remind_at) VALUES('00000000-0000-0000-0000-000000000991','TASK',gen_random_uuid(),'维护提醒',now(),now())")
	exec("INSERT INTO web_push_subscription(id,endpoint,p256dh,auth,created_at) VALUES('00000000-0000-0000-0000-000000000992','https://push.invalid/fixture','fixture','fixture',now())")
	exec("INSERT INTO web_push_delivery(subscription_id,reminder_id,state,attempts,next_attempt_at,created_at) VALUES('00000000-0000-0000-0000-000000000992','00000000-0000-0000-0000-000000000991','FAILED',3,now(),now())")
	exec("INSERT INTO reminder_webhook_target(target) VALUES('maintenance-fixture')")
	exec("INSERT INTO reminder_webhook_delivery(target,reminder_id,state,attempts) VALUES('maintenance-fixture','00000000-0000-0000-0000-000000000991','FAILED',3)")
	exec("INSERT INTO chat_session(conversation_id,title) VALUES('maintenance-interrupted','维护中断样例'),('maintenance-completed','已完成样例')")
	exec("INSERT INTO chat_run(id,conversation_id,request,status,created_at) VALUES('maintenance-run','maintenance-interrupted','{}','INTERRUPTED',now()),('maintenance-old','maintenance-completed','{}','STOPPED',now()-interval '1 day'),('maintenance-new','maintenance-completed','{}','COMPLETED',now())")
	exec("INSERT INTO workflow(name,kind,timezone,local_time) VALUES('维护失败样例','MORNING_BRIEF','UTC','09:00')")
	exec("INSERT INTO workflow_execution(workflow_id,scheduled_for,source,definition,status,error) SELECT id,now(),'MANUAL',to_jsonb(w),'FAILED','今日执行次数已达上限' FROM workflow w WHERE name='维护失败样例'")
	snapshot, e = s.Snapshot(ctx)
	if e != nil {
		t.Fatal(e)
	}
	if snapshot["failedDeliveries"].(map[string]any)["total"] != float64(2) || snapshot["unfinishedRuns"].(map[string]any)["total"] != float64(1) || snapshot["failedWorkflows"].(map[string]any)["total"] != float64(1) {
		t.Fatal(snapshot)
	}
	// 历史有界，原始正文从不保存。
	for range 205 {
		if _, e = s.Verify(ctx, []byte("marker-private-backup-content")); e != nil {
			t.Fatal(e)
		}
	}
	if scalar("SELECT count(*)::text FROM maintenance_check") != "200" || scalar("SELECT count(*)::text FROM maintenance_check WHERE result::text LIKE '%marker-private%'") != "0" {
		t.Fatal("unbounded or raw history")
	}
}
