//go:build integration

package app

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

func appPostgresOptions(t *testing.T) database.PostgresOptions {
	t.Helper()
	// 复用已有守卫，任何参数都只能来自 runner 新建的隔离集群。
	_ = fixtureAppConnector(t, "go-options-guard")
	o := database.DefaultPostgresOptions()
	o.Host, o.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	o.Username, o.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	o.Port, _ = strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	o.SSLMode, o.RootCert = os.Getenv("GO_TEST_POSTGRES_SSL_MODE"), os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	return o
}

func TestFoundationLeastPrivilegeAcrossMigratedSchema(t *testing.T) {
	admin := fixtureFoundation(t, nil, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var secret [24]byte
	if _, err := rand.Read(secret[:]); err != nil {
		t.Fatal(err)
	}
	password := hex.EncodeToString(secret[:])
	role := "go_foundation_" + password[:12]
	appExec(t, admin, "CREATE ROLE "+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'")
	t.Cleanup(func() {
		if err := admin.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
			_, err := c.ExecContext(context.Background(), "DROP OWNED BY "+role+"; DROP ROLE "+role)
			return err
		}); err != nil {
			t.Error("清理本次隔离角色失败", err)
		}
	})
	appExec(t, admin, `REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE personalos_go_test FROM PUBLIC;
GRANT CONNECT ON DATABASE personalos_go_test TO `+role+`;
GRANT USAGE ON SCHEMA public TO `+role+`;
GRANT SELECT ON public.flyway_schema_history TO `+role+`;
GRANT SELECT,INSERT,UPDATE ON public.runtime_rate_bucket,public.runtime_lease TO `+role+`;
DELETE FROM runtime_rate_bucket;`)
	options := appPostgresOptions(t)
	options.Username, options.Password = role, password
	options.ApplicationName = "go-foundation-acl"
	connector, err := database.NewPostgresConnector(options)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	open := func(role config.Role) (*Foundation, error) {
		p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.runtime.role": string(role), "app.rate-limit.capacity": "100", "app.model-max-concurrent-requests": "2"}})
		if err != nil {
			return nil, err
		}
		return OpenFoundation(ctx, p, FoundationOptions{Connector: connector, Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", Business: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })}, logger)
	}
	f, err := open(config.API)
	if err != nil {
		t.Fatal("最小权限不能启动已迁移的真实schema", err)
	}
	defer f.Close(context.Background())
	if f.History().Applied != 42 {
		t.Fatal("受限角色未读取完整Flyway历史")
	}
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	if w.Code != 204 {
		t.Fatal("受限角色共享桶/模型租约操作失败", w.Code)
	}
	if err := f.Pool().WithConn(ctx, func(c *sql.Conn) error {
		var permitted bool
		if err := c.QueryRowContext(ctx, `SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=current_user`).Scan(&permitted); err != nil {
			return err
		}
		if !permitted {
			return errors.New("运行角色含管理权限")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	var tables []string
	if err := admin.Pool().WithConn(ctx, func(c *sql.Conn) error {
		rows, err := c.QueryContext(ctx, `SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename`)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var table string
			if err := rows.Scan(&table); err != nil {
				return err
			}
			tables = append(tables, table)
		}
		return rows.Err()
	}); err != nil {
		t.Fatal(err)
	}
	if len(tables) != 40 {
		t.Fatal("未检查完整42迁移后的40表（含历史）", len(tables))
	}
	for _, table := range tables {
		if table == "flyway_schema_history" || table == "runtime_rate_bucket" || table == "runtime_lease" {
			continue
		}
		statement := `SELECT 1 FROM public."` + strings.ReplaceAll(table, `"`, `""`) + `" LIMIT 0`
		err := f.Pool().WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, statement); return err })
		var pg *pgconn.PgError
		if !errors.As(err, &pg) || pg.Code != "42501" {
			t.Fatal("P1最小角色不应读取未迁移业务表", table, err)
		}
	}
	for _, statement := range []string{
		"CREATE ROLE go_forbidden_escalation", "CREATE SCHEMA go_forbidden_schema", "CREATE TEMP TABLE go_forbidden_temp(id int)",
		"CREATE TABLE public.go_forbidden_table(id int)", "ALTER TABLE runtime_lease ADD forbidden int", "TRUNCATE runtime_rate_bucket",
		"DELETE FROM runtime_lease", "UPDATE flyway_schema_history SET success=false", "ALTER ROLE " + role + " SUPERUSER",
	} {
		err := f.Pool().WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, statement); return err })
		var pg *pgconn.PgError
		if !errors.As(err, &pg) || pg.Code != "42501" || strings.Contains(err.Error(), password) {
			t.Fatal("受限操作未正确拒绝/脱敏", strings.Fields(statement)[0], err)
		}
	}
	if err := f.Close(ctx); err != nil {
		t.Fatal(err)
	}
	for _, role := range []config.Role{config.Combined, config.Index, config.Review, config.Delivery} {
		instance, err := open(role)
		if err != nil {
			t.Fatal("受限角色装配失败", role, err)
		}
		w := httptest.NewRecorder()
		instance.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
		if role.Worker() && (w.Code != 404 || w.Body.Len() != 0) || !role.Worker() && w.Code != 200 {
			t.Fatal("受限角色隔离错误", role, w.Code)
		}
		if err := instance.Close(ctx); err != nil {
			t.Fatal(err)
		}
	}
	appExec(t, admin, "REVOKE SELECT ON flyway_schema_history FROM "+role)
	if failed, err := open(config.API); err == nil || failed != nil {
		t.Fatal("缺少历史读取权限必须启动拒绝")
	}
	if err := admin.Pool().WithConn(ctx, func(c *sql.Conn) error {
		var count int
		if err := c.QueryRowContext(ctx, `SELECT count(*) FROM pg_stat_activity WHERE application_name='go-foundation-acl'`).Scan(&count); err != nil {
			return err
		}
		if count != 0 {
			return errors.New("受限实例关闭/启动失败后连接泄露")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
