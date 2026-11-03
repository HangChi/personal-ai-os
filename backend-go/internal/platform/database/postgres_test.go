package database

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func localPostgresOptions() PostgresOptions {
	options := DefaultPostgresOptions()
	options.Host, options.Database, options.Username, options.Password = "127.0.0.1", "fixture_db", "fixture_user", "fixture-secret"
	options.SSLMode = "disable"
	return options
}

func TestPostgresOptionsFailBeforeConnecting(t *testing.T) {
	for _, test := range []struct {
		name   string
		change func(*PostgresOptions)
	}{
		{"empty host", func(o *PostgresOptions) { o.Host = "" }},
		{"multiple hosts", func(o *PostgresOptions) { o.Host = "localhost,other" }},
		{"socket path", func(o *PostgresOptions) { o.Host = "/tmp" }},
		{"embedded port", func(o *PostgresOptions) { o.Host = "localhost:5432" }},
		{"zero port", func(o *PostgresOptions) { o.Port = 0 }},
		{"port overflow", func(o *PostgresOptions) { o.Port = 65536 }},
		{"missing database", func(o *PostgresOptions) { o.Database = "" }},
		{"missing username", func(o *PostgresOptions) { o.Username = "" }},
		{"missing password", func(o *PostgresOptions) { o.Password = "" }},
		{"NUL", func(o *PostgresOptions) { o.Password += "\x00" }},
		{"TLS downgrade", func(o *PostgresOptions) { o.SSLMode = "prefer" }},
		{"remote without TLS", func(o *PostgresOptions) { o.Host = "db.example.invalid" }},
		{"missing timeout", func(o *PostgresOptions) { o.ConnectTimeout = 0 }},
		{"application name injection", func(o *PostgresOptions) { o.ApplicationName = "secret\x00name" }},
	} {
		t.Run(test.name, func(t *testing.T) {
			options := localPostgresOptions()
			test.change(&options)
			connector, err := NewPostgresConnector(options)
			if err == nil || connector != nil {
				t.Fatal("配置错误必须在连接之前失败")
			}
			if strings.Contains(err.Error(), "fixture-secret") {
				t.Fatal("错误不能回显密码")
			}
		})
	}
}

func TestPostgresConnectorUsesExplicitConfigAndUTC(t *testing.T) {
	for _, host := range []string{"127.0.0.1", "::1", "localhost"} {
		options := localPostgresOptions()
		options.Host = host
		options.Password = "quote' slash\\ space + 口令"
		config, err := postgresConfig(options)
		if err != nil {
			t.Fatal(err)
		}
		if config.Host != host || config.Port != 5432 || config.Database != "fixture_db" || config.User != "fixture_user" || config.Password != options.Password || config.ConnectTimeout != 5*time.Second || config.TLSConfig != nil || len(config.Fallbacks) != 0 {
			t.Fatal("驱动设置必须完全来自显式输入，不能有隐式回退")
		}
		if config.RuntimeParams["timezone"] != "UTC" || config.RuntimeParams["application_name"] != "personalos-go" || len(config.RuntimeParams) != 2 {
			t.Fatal("连接应固定 UTC 和应用名，不能继承 PGOPTIONS")
		}
	}
}

func TestPostgresRejectsImplicitEnvironment(t *testing.T) {
	for _, key := range []string{"PGHOST", "PGSERVICE", "PGPASSFILE", "PGOPTIONS", "PGTZ", "PGSSLCERTMODE", "PGFUTURE_SETTING"} {
		t.Run(key, func(t *testing.T) {
			t.Setenv(key, "private-value")
			connector, err := NewPostgresConnector(localPostgresOptions())
			if err == nil || connector != nil || strings.Contains(err.Error(), "private-value") {
				t.Fatal("不能继承隐式 PG* 来源或回显值")
			}
		})
	}
}

func TestPostgresRedactsFormattingAndJSON(t *testing.T) {
	options := localPostgresOptions()
	connector, err := NewPostgresConnector(options)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(options)
	if err != nil {
		t.Fatal(err)
	}
	for _, output := range []string{fmt.Sprint(options), fmt.Sprintf("%+v", options), fmt.Sprintf("%#v", options), fmt.Sprintf("%+v", connector), fmt.Sprintf("%#v", connector), string(encoded)} {
		for _, private := range []string{options.Host, options.Database, options.Username, options.Password} {
			if strings.Contains(output, private) {
				t.Fatalf("格式化泄露连接信息：%s", output)
			}
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = connector.Connect(ctx)
	if err == nil || strings.Contains(fmt.Sprintf("%v %#v", err, err), options.Password) {
		t.Fatal("连接错误必须脱敏")
	}
}

func TestPostgresVerifyFullLoadsOnlyExplicitRoot(t *testing.T) {
	options := localPostgresOptions()
	options.Host, options.SSLMode = "db.example.invalid", "verify-full"
	config, err := postgresConfig(options)
	if err != nil {
		t.Fatal(err)
	}
	if config.TLSConfig == nil || config.TLSConfig.InsecureSkipVerify || config.TLSConfig.ServerName != options.Host || len(config.Fallbacks) != 0 {
		t.Fatal("verify-full 必须验证证书链/主机名且不能回退明文")
	}
	root := filepath.Join(t.TempDir(), "root.pem")
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture CA"}, IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(root, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil {
		t.Fatal(err)
	}
	options.RootCert = root
	config, err = postgresConfig(options)
	if err != nil || config.TLSConfig.RootCAs == nil {
		t.Fatal("显式根证书应被加载")
	}
	if err := os.WriteFile(root, []byte("not a certificate"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewPostgresConnector(options); err == nil {
		t.Fatal("非法 PEM 不能静默回落系统证书")
	}
	options.RootCert += ".missing"
	if _, err := NewPostgresConnector(options); err == nil || strings.Contains(err.Error(), options.RootCert) {
		t.Fatal("证书读取失败应脱敏")
	}
}
