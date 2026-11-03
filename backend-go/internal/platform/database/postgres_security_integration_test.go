//go:build integration

package database

import (
	"context"
	"crypto/rand"
	"crypto/x509"
	"database/sql"
	"encoding/hex"
	"errors"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/stdlib"
)

func securityFixtureOptions(t *testing.T) PostgresOptions {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("仅使用专用临时数据库")
	}
	settings := DefaultPostgresOptions()
	settings.Host, settings.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	settings.Username, settings.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || settings.Host != "127.0.0.1" || settings.Database != "personalos_go_test" || settings.Username != "go_fixture" {
		t.Fatal("不是专用测试数据库")
	}
	settings.Port = port
	settings.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	if settings.SSLMode == "" {
		settings.SSLMode = "disable"
	}
	settings.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	return settings
}

func TestPostgresTLSCertificateHostnameAndNoPlaintext(t *testing.T) {
	settings := securityFixtureOptions(t)
	if settings.SSLMode != "verify-full" {
		t.Skip("需 --tls fixture")
	}
	pool := fixturePostgres(t, DefaultPoolOptions(true))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		var ssl bool
		var version string
		if err := conn.QueryRowContext(ctx, "SELECT ssl,version FROM pg_stat_ssl WHERE pid=pg_backend_pid()").Scan(&ssl, &version); err != nil {
			return err
		}
		if !ssl || (version != "TLSv1.2" && version != "TLSv1.3") {
			return errors.New("实际连接未使用受支持 TLS")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	untrusted := settings
	untrusted.RootCert = ""
	connector, err := NewPostgresConnector(untrusted)
	if err != nil {
		t.Fatal(err)
	}
	failed, err := Open(ctx, connector, DefaultPoolOptions(true))
	if failed != nil {
		failed.Close()
		t.Fatal("未知 CA 被接受")
	}
	var unknown x509.UnknownAuthorityError
	if !errors.As(err, &unknown) {
		t.Fatal("未知 CA 未产生证书拒绝", err)
	}
	config, err := postgresConfig(settings)
	if err != nil {
		t.Fatal(err)
	}
	config.TLSConfig.ServerName = "different-fixture.example"
	connector = &postgresConnector{inner: stdlib.GetConnector(*config)}
	failed, err = Open(ctx, connector, DefaultPoolOptions(true))
	if failed != nil {
		failed.Close()
		t.Fatal("错误主机名被接受")
	}
	var mismatch x509.HostnameError
	if !errors.As(err, &mismatch) {
		t.Fatal("主机名不匹配未被拒绝", err)
	}
	plaintext := settings
	plaintext.SSLMode = "disable"
	plaintext.RootCert = ""
	connector, err = NewPostgresConnector(plaintext)
	if err != nil {
		t.Fatal(err)
	}
	failed, err = Open(ctx, connector, DefaultPoolOptions(true))
	if failed != nil {
		failed.Close()
		t.Fatal("TLS 测试集群接受了明文")
	}
	if err == nil {
		t.Fatal("明文错误缺失")
	}
	if err := pool.Ping(ctx); err != nil {
		t.Fatal("证书失败影响了正常连接池", err)
	}
}

// 此处的最小权限只覆盖事务/数据类型探针；不是全量 42 个 schema 的部署角色方案。
func TestPostgresLeastPrivilegeApplicationRole(t *testing.T) {
	admin := fixturePostgres(t, DefaultPoolOptions(true))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var secret [24]byte
	if _, err := rand.Read(secret[:]); err != nil {
		t.Fatal(err)
	}
	password := hex.EncodeToString(secret[:])
	err := admin.WithConn(ctx, func(conn *sql.Conn) error {
		// 标识符为固定测试常量，唯一拼接值为本次生成且仅含 hex 的密码。
		_, err := conn.ExecContext(ctx, "CREATE ROLE go_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'")
		if err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx, `CREATE SCHEMA go_security_probe AUTHORIZATION go_fixture;
CREATE TABLE go_security_probe.allowed(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,value text NOT NULL);
CREATE TABLE go_security_probe.denied(id integer);
REVOKE ALL ON SCHEMA go_security_probe FROM PUBLIC;
GRANT USAGE ON SCHEMA go_security_probe TO go_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON go_security_probe.allowed TO go_runtime;
GRANT USAGE,SELECT ON SEQUENCE go_security_probe.allowed_id_seq TO go_runtime;`)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	settings := securityFixtureOptions(t)
	settings.Username = "go_runtime"
	settings.Password = password
	connector, err := NewPostgresConnector(settings)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := Open(ctx, connector, DefaultPoolOptions(true))
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	err = pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var id int64
		var value string
		if err := tx.QueryRowContext(ctx, "INSERT INTO go_security_probe.allowed(value) VALUES($1) RETURNING id", "中文🔐").Scan(&id); err != nil {
			return err
		}
		if err := tx.QueryRowContext(ctx, "SELECT value FROM go_security_probe.allowed WHERE id=$1", id).Scan(&value); err != nil {
			return err
		}
		if value != "中文🔐" {
			return errors.New("受限角色写入数据不一致")
		}
		_, err := tx.ExecContext(ctx, "UPDATE go_security_probe.allowed SET value=$1 WHERE id=$2", "updated", id)
		return err
	})
	if err != nil {
		t.Fatal("受限角色事务失败", err)
	}
	err = pool.WithConn(ctx, func(conn *sql.Conn) error {
		var allowed bool
		if err := conn.QueryRowContext(ctx, `SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=current_user`).Scan(&allowed); err != nil {
			return err
		}
		if !allowed {
			return errors.New("测试角色含管理权限")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{"CREATE ROLE go_runtime_escalated", "CREATE DATABASE go_runtime_created", "CREATE TABLE go_security_probe.forbidden(id integer)", "DROP TABLE go_security_probe.allowed", "SELECT * FROM go_security_probe.denied", "ALTER ROLE go_runtime SUPERUSER"} {
		err := pool.WithConn(ctx, func(conn *sql.Conn) error { _, err := conn.ExecContext(ctx, statement); return err })
		var pgError *pgconn.PgError
		if !errors.As(err, &pgError) || pgError.Code != "42501" {
			t.Fatalf("受限操作未按权限拒绝: %s: %v", strings.Fields(statement)[0], err)
		}
		if strings.Contains(err.Error(), password) {
			t.Fatal("凭据出现在错误响应")
		}
	}
	if err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "DELETE FROM go_security_probe.allowed")
		return err
	}); err != nil {
		t.Fatal("拒绝后正常权限不可用", err)
	}
}
