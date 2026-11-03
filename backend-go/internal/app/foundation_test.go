package app

import (
	"context"
	"database/sql/driver"
	"io"
	"log/slog"
	"testing"
	"testing/fstest"

	"personal-ai-os/backend-go/internal/platform/config"
)

type forbiddenConnector struct{ t *testing.T }

func (c forbiddenConnector) Connect(context.Context) (driver.Conn, error) {
	c.t.Fatal("配置拒绝必须早于连接数据库")
	return nil, nil
}
func (c forbiddenConnector) Driver() driver.Driver { return nil }

func TestFoundationRejectsBeforeConnection(t *testing.T) {
	for _, test := range []struct {
		name   string
		values map[string]string
		schema string
	}{
		{"production", map[string]string{"app.security.production-mode": "true"}, "public"},
		{"missing session redis", map[string]string{"app.shared-session.enabled": "true"}, "public"},
		{"missing rate redis", map[string]string{"app.rate-limit.redis.enabled": "true"}, "public"},
		{"unsupported capacity", map[string]string{"app.model-max-concurrent-requests": "101"}, "public"},
		{"schema mismatch", nil, "another"},
	} {
		t.Run(test.name, func(t *testing.T) {
			p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: test.values})
			if err != nil {
				t.Fatal(err)
			}
			f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: forbiddenConnector{t}, Schema: test.schema, Migrations: fstest.MapFS{"V1__fixture.sql": {Data: []byte("SELECT 1;")}}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if err == nil || f != nil {
				t.Fatal("不完整配置不能创建基础运行时")
			}
		})
	}
}
