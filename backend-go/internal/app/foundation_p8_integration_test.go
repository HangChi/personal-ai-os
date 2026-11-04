//go:build integration

package app

import (
	"context"
	"database/sql"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/maintenance"
)

func TestP8RetentionCutoffsAndUncertainTools(t *testing.T) {
	f := p7Foundation(t, nil)
	appExec(t, f, `DELETE FROM token_usage;
	 INSERT INTO token_usage(conversation_id,prompt_tokens,completion_tokens,source,created_at) VALUES
	 ('retention',1,1,'ACTUAL','2026-05-04T23:59:59.999999Z'),('retention',1,1,'ESTIMATED','2026-05-05Z'),('retention',1,1,'ACTUAL','2026-10-31Z');
	 INSERT INTO operation_record(idempotency_key,source,operation,request_digest,outcome,created_at) VALUES
	 ('old-http','HTTP','fixture','digest','SUCCESS','2026-10-01Z'),('boundary-http','HTTP','fixture','digest','SUCCESS','2026-10-02Z'),
	 ('old-tool-unknown','TOOL','fixture','digest','IN_PROGRESS','2020-01-01Z'),('old-tool-success','TOOL','fixture','digest','SUCCESS','2026-10-01Z');`)
	t.Cleanup(func() { appExec(t, f, `DELETE FROM token_usage`) })
	now := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	store := maintenance.NewPostgresStore(f.pool)
	service, err := maintenance.New(store, config.RetentionProperties{}, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	counts, err := service.Cleanup(context.Background())
	if err != nil || counts != (maintenance.Counts{}) || p7Count(t, f, "token_usage") != 3 || p7Count(t, f, "operation_record") != 4 {
		t.Fatal(counts, err)
	}
	service, err = maintenance.New(store, config.RetentionProperties{TokenUsageDays: 180, OperationRecordDays: 30}, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	counts, err = service.Cleanup(context.Background())
	if err != nil || counts.Usage != 1 || counts.Operations != 2 || p7Count(t, f, "token_usage") != 2 || p7Count(t, f, "operation_record") != 2 {
		t.Fatal(counts, err)
	}
	var unresolved int
	err = f.pool.WithConn(context.Background(), func(c *sql.Conn) error {
		return c.QueryRowContext(context.Background(), "SELECT count(*) FROM operation_record WHERE idempotency_key='old-tool-unknown' AND source='TOOL' AND outcome='IN_PROGRESS'").Scan(&unresolved)
	})
	if err != nil || unresolved != 1 {
		t.Fatal(unresolved, err)
	}
	counts, err = service.Cleanup(context.Background())
	if err != nil || counts != (maintenance.Counts{}) {
		t.Fatal(counts, err)
	}
}

func TestP8RetentionFoundationValidationAndShutdown(t *testing.T) {
	var options FoundationOptions
	options = FoundationOptions{Connector: fixtureAppConnector(t, "go-p8-retention"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true, EnableP5: true, EnableP6: true, EnableP7: true, EnableP8: true}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, v := range []map[string]string{{"app.retention.token-usage-days": "-1"}, {"app.retention.operation-record-days": "-1"}, {"app.retention.cron": "bad"}} {
		p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: v})
		if err != nil {
			t.Fatal(err)
		}
		if _, err = OpenFoundation(context.Background(), p, options, logger); err == nil {
			t.Fatal("invalid retention accepted")
		}
	}
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.chat.upload-dir": t.TempDir(), "app.context.summary.enabled": "false", "app.context.auto-memory.enabled": "false", "app.memory.backfill.enabled": "false", "app.calendar.scan-enabled": "false", "app.retention.cron": "* * * * * *", "app.retention.token-usage-days": "1"}})
	if err != nil {
		t.Fatal(err)
	}
	f, err := OpenFoundation(context.Background(), p, options, logger)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close(context.Background())
	appExec(t, f, `DELETE FROM token_usage; INSERT INTO token_usage(conversation_id,prompt_tokens,completion_tokens,source,created_at) VALUES('p8-scheduler',1,1,'ACTUAL','2000-01-01Z')`)
	deadline := time.Now().Add(5 * time.Second)
	for p7Count(t, f, "token_usage") != 0 {
		if time.Now().After(deadline) {
			t.Fatal("P8 retention worker not wired")
		}
		time.Sleep(20 * time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err = f.Close(ctx); err != nil {
		t.Fatal(err)
	}
	// 日志与错误不可包含底层 SQL/行内容；关闭后的再调用也应确定返回。
	if err = f.Close(ctx); err != nil && strings.Contains(err.Error(), "token_usage") {
		t.Fatal(err)
	}
}
