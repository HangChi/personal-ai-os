//go:build integration

package task

import (
	"context"
	"crypto/rand"
	"database/sql"
	"database/sql/driver"
	"encoding/hex"
	"fmt"
	"os"
	"personal-ai-os/backend-go/internal/platform/database"
	"strconv"
	"testing"
	"time"
)

func fixturePool(t *testing.T) *database.Pool {
	return fixturePoolWith(t, nil)
}

func fixturePoolWith(t *testing.T, wrap func(driver.Connector) driver.Connector) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需独立Flyway测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许独立测试库")
	}
	opts.Port = port
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var source driver.Connector = connector
	if wrap != nil {
		source = wrap(source)
	}
	pool, err := database.Open(ctx, source, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	})
	return pool
}
func TestP2LeastPrivilegeRole(t *testing.T) {
	owner := fixturePool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	suffix := make([]byte, 4)
	if _, err := rand.Read(suffix); err != nil {
		t.Fatal(err)
	}
	role := "go_p2_" + hex.EncodeToString(suffix)
	password := "fixture_p2_only"
	exec := func(statement string) {
		t.Helper()
		if err := owner.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, statement); return err }); err != nil {
			t.Fatal(err)
		}
	}
	exec(fmt.Sprintf("CREATE ROLE %s LOGIN PASSWORD '%s'", role, password))
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = owner.WithConn(cleanup, func(c *sql.Conn) error {
			_, err := c.ExecContext(cleanup, "DROP OWNED BY "+role+"; DROP ROLE "+role)
			return err
		})
	})
	options := database.DefaultPostgresOptions()
	options.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	options.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	options.Username = role
	options.Password = password
	options.Port, _ = strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	options.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	options.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(options)
	if err != nil {
		t.Fatal(err)
	}
	runtime, err := database.Open(ctx, connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	defer runtime.Close()
	if err := CheckPrivileges(ctx, runtime); err == nil {
		t.Fatal("P1-only role unexpectedly passed P2 grants")
	}
	exec("GRANT USAGE ON SCHEMA public TO " + role)
	for _, item := range []struct{ rights, table string }{{"SELECT,INSERT,UPDATE,DELETE", "task"}, {"SELECT,INSERT,DELETE", "task_tag"}, {"SELECT,INSERT,UPDATE,DELETE", "operation_record"}, {"SELECT,INSERT,UPDATE", "confirmation"}, {"SELECT,UPDATE,DELETE", "reminder"}} {
		exec("GRANT " + item.rights + " ON public." + item.table + " TO " + role)
	}
	exec("GRANT USAGE ON SEQUENCE public.operation_record_id_seq TO " + role)
	if err := CheckPrivileges(ctx, runtime); err != nil {
		t.Fatal(err)
	}
	store, _ := NewPostgresStore(runtime)
	service, _ := NewService(store)
	created, err := service.Create(ctx, CreateRequest{Title: "受限角色任务"})
	if err != nil {
		t.Fatal(err)
	}
	if err := service.Delete(ctx, created.ID); err != nil {
		t.Fatal(err)
	}
}
func TestTaskPendingReminderSynchronization(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	store, _ := NewPostgresStore(fixturePool(t))
	service, _ := NewService(store)
	due := time.Now().UTC().Add(time.Hour).Truncate(time.Microsecond)
	created, err := service.Create(ctx, CreateRequest{Title: "提醒旧标题", DueAt: &due})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = service.Delete(context.Background(), created.ID) })
	err = store.pool.WithConn(ctx, func(c *sql.Conn) error {
		_, err := c.ExecContext(ctx, `INSERT INTO reminder(source_type,source_id,title,occurrence_at,remind_at,status) VALUES('TASK',$1::uuid,'提醒旧标题',$2,$2,'PENDING')`, created.ID, due)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	title := "提醒新标题"
	if _, err := service.Update(ctx, created.ID, UpdateRequest{Title: &title}); err != nil {
		t.Fatal(err)
	}
	var stored string
	err = store.pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `SELECT title FROM reminder WHERE source_type='TASK' AND source_id=$1::uuid`, created.ID).Scan(&stored)
	})
	if err != nil || stored != title {
		t.Fatalf("reminder title=%q err=%v", stored, err)
	}
	if _, err := service.SetCompleted(ctx, created.ID, true); err != nil {
		t.Fatal(err)
	}
	var remaining int
	err = store.pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `SELECT count(*) FROM reminder WHERE source_type='TASK' AND source_id=$1::uuid`, created.ID).Scan(&remaining)
	})
	if err != nil || remaining != 0 {
		t.Fatalf("remaining reminders=%d err=%v", remaining, err)
	}
}
func TestPostgresTaskCRUDAndSearch(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	store, _ := NewPostgresStore(fixturePool(t))
	if err := CheckPrivileges(ctx, store.pool); err != nil {
		t.Fatal(err)
	}
	service, _ := NewService(store)
	title := "合成任务P2"
	created, err := service.Create(ctx, CreateRequest{Title: title, Tags: []string{" 工作 ", "工作", "学习"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = service.Delete(context.Background(), created.ID) })
	if created.Status != Todo || created.Priority != Medium {
		t.Fatalf("created=%+v", created)
	}
	loaded, err := service.Get(ctx, created.ID)
	if err != nil || len(loaded.Tags) != 2 || loaded.Tags[0] != "工作" {
		t.Fatalf("tags=%v err=%v", loaded.Tags, err)
	}
	if tags, err := service.ListTags(ctx); err != nil || len(tags) < 2 {
		t.Fatalf("tags list=%v err=%v", tags, err)
	}
	omitted, err := service.Update(ctx, created.ID, UpdateRequest{Title: &title})
	if err != nil || len(omitted.Tags) != 2 {
		t.Fatalf("omitted tags=%v err=%v", omitted.Tags, err)
	}
	empty := []string{}
	cleared, err := service.Update(ctx, created.ID, UpdateRequest{Tags: &empty})
	if err != nil || len(cleared.Tags) != 0 {
		t.Fatalf("clear tags=%v err=%v", cleared.Tags, err)
	}
	batch, err := (ExportContributor{Tasks: service}).Batch(ctx, 0, 100)
	if err != nil || len(batch) == 0 {
		t.Fatalf("export=%d err=%v", len(batch), err)
	}
	hits, err := (SearchContributor{Tasks: service}).Search(ctx, "合成任务P2", 10)
	if err != nil || len(hits) == 0 || hits[0].MatchedField != "标题" {
		t.Fatalf("hits=%v err=%v", hits, err)
	}
	doing := Doing
	updated, err := service.Update(ctx, created.ID, UpdateRequest{Status: &doing})
	if err != nil || updated.Status != Doing {
		t.Fatalf("updated=%+v err=%v", updated, err)
	}
	done, err := service.SetCompleted(ctx, created.ID, true)
	if err != nil || done.Status != Done || done.PreviousStatus == nil || *done.PreviousStatus != Doing {
		t.Fatalf("done=%+v err=%v", done, err)
	}
	reopened, err := service.SetCompleted(ctx, created.ID, false)
	if err != nil || reopened.Status != Doing {
		t.Fatalf("reopened=%+v err=%v", reopened, err)
	}
	page, err := service.Search(ctx, Query{Keyword: "合成任务P2", Size: 20})
	if err != nil || page.Total < 1 {
		t.Fatalf("page=%+v err=%v", page, err)
	}
	byIDs, err := service.FindByIDs(ctx, []string{created.ID, "00000000-0000-0000-0000-000000000000"})
	if err != nil || len(byIDs) != 1 || byIDs[0].ID != created.ID {
		t.Fatalf("find by ids=%+v err=%v", byIDs, err)
	}
	stats, err := service.Stats(ctx)
	if err != nil || stats.Pending < 1 {
		t.Fatalf("stats=%+v err=%v", stats, err)
	}
	if err := service.Delete(ctx, created.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Get(ctx, created.ID); err != ErrNotFound {
		t.Fatalf("after delete=%v", err)
	}
}
