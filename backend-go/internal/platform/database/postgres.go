package database

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"database/sql/driver"
	"errors"
	"net"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/stdlib"
)

// PostgresOptions 只能由显式配置创建，不从 PG*、.pgpass 或 service 文件补全。
// 不提供默认主机/库/用户/密码，也不接受任意 DSN，防止误连接现有数据库。
type PostgresOptions struct {
	Host            string
	Port            int
	Database        string
	Username        string
	Password        string
	SSLMode         string
	RootCert        string
	ConnectTimeout  time.Duration
	ApplicationName string
}

func DefaultPostgresOptions() PostgresOptions {
	return PostgresOptions{Port: 5432, SSLMode: "verify-full", ConnectTimeout: 5 * time.Second, ApplicationName: "personalos-go"}
}

func (PostgresOptions) String() string               { return "PostgresOptions{redacted}" }
func (PostgresOptions) GoString() string             { return "PostgresOptions{redacted}" }
func (PostgresOptions) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

func (options PostgresOptions) validate() error {
	if options.Host == "" || strings.ContainsAny(options.Host, "\x00 \t\r\n/,[]") ||
		(strings.Contains(options.Host, ":") && net.ParseIP(options.Host) == nil) {
		return errors.New("PostgreSQL 必须显式提供单一 TCP 主机，不支持隐式端口或 Unix socket")
	}
	if options.Port < 1 || options.Port > 65535 {
		return errors.New("PostgreSQL 端口必须为 1–65535")
	}
	for _, value := range []string{options.Database, options.Username, options.Password, options.ApplicationName} {
		if value == "" || strings.ContainsRune(value, '\x00') || !utf8.ValidString(value) {
			return errors.New("PostgreSQL 数据库/用户/密码/应用名必须显式设置且不能包含 NUL 或非法 UTF-8")
		}
	}
	if options.ConnectTimeout <= 0 {
		return errors.New("PostgreSQL 连接期限必须为正时长")
	}
	switch options.SSLMode {
	case "verify-full":
	case "disable":
		ip := net.ParseIP(options.Host)
		if options.Host != "localhost" && (ip == nil || !ip.IsLoopback()) {
			return errors.New("PostgreSQL 明文连接只允许显式回环测试主机")
		}
		if options.RootCert != "" {
			return errors.New("PostgreSQL 明文连接不能配置根证书")
		}
	default:
		return errors.New("PostgreSQL SSLMode 当前只支持 verify-full 或回环测试 disable")
	}
	return nil
}

// NewPostgresConnector 构建真实 pgx database/sql 适配器；不发起连接或执行迁移。
// Open 继续负责池限制、启动 Ping 与超时。应用入口尚未调用本函数。
func NewPostgresConnector(options PostgresOptions) (driver.Connector, error) {
	config, err := postgresConfig(options)
	if err != nil {
		return nil, err
	}
	return &postgresConnector{inner: stdlib.GetConnector(*config, stdlib.OptionAfterConnect(func(_ context.Context, connection *pgx.Conn) error {
		// PostgreSQL 会话时区不控制 pgx 的二进制扫描位置；显式保持 UTC 输出。
		connection.TypeMap().RegisterType(&pgtype.Type{Name: "timestamptz", OID: pgtype.TimestamptzOID, Codec: &pgtype.TimestamptzCodec{ScanLocation: time.UTC}})
		return nil
	}))}, nil
}

func postgresConfig(options PostgresOptions) (*pgx.ConnConfig, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}
	// pgx 的解析器会读取 libpq 环境；不临时清理进程全局环境，以免并发串扰。
	// 发现隐式 PG* 来源便拒绝。随后只解析固定、非敏感、禁用 TLS 的种子，
	// 设置完整显式参数与 TLS，避免解析器查找用户的 service/pass/cert 文件。
	for _, entry := range os.Environ() {
		key, _, _ := strings.Cut(entry, "=")
		if strings.HasPrefix(key, "PG") {
			return nil, errors.New("PostgreSQL 不允许隐式 PG* 环境，请使用显式连接配置")
		}
	}
	config, err := pgx.ParseConfig("host=127.0.0.1 port=1 dbname=go_seed user=go_seed password=not-used sslmode=disable")
	if err != nil {
		return nil, operationError("解析驱动配置", err)
	}
	config.Host, config.Port, config.Database = options.Host, uint16(options.Port), options.Database
	config.User, config.Password = options.Username, options.Password
	config.ConnectTimeout = options.ConnectTimeout
	config.DialFunc = (&net.Dialer{Timeout: options.ConnectTimeout, KeepAlive: 5 * time.Minute}).DialContext
	config.RuntimeParams = map[string]string{"timezone": "UTC", "application_name": options.ApplicationName}
	config.Fallbacks = nil
	if options.SSLMode == "verify-full" {
		config.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12, ServerName: options.Host}
		if options.RootCert != "" {
			data, readErr := os.ReadFile(options.RootCert)
			if readErr != nil {
				return nil, operationError("读取根证书", readErr)
			}
			roots := x509.NewCertPool()
			if !roots.AppendCertsFromPEM(data) {
				return nil, errors.New("PostgreSQL 根证书内容无效")
			}
			config.TLSConfig.RootCAs = roots
		}
	}
	return config, nil
}

type postgresConnector struct{ inner driver.Connector }

func (*postgresConnector) String() string                  { return "PostgresConnector{redacted}" }
func (*postgresConnector) GoString() string                { return "PostgresConnector{redacted}" }
func (connector *postgresConnector) Driver() driver.Driver { return connector.inner.Driver() }
func (connector *postgresConnector) Connect(ctx context.Context) (driver.Conn, error) {
	connection, err := connector.inner.Connect(ctx)
	if err != nil {
		return nil, operationError("连接", err)
	}
	return connection, nil
}
