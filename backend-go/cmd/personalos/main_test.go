package main

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
)

func TestRunRejectsProductionBeforeOpeningListener(t *testing.T) {
	for key, value := range map[string]string{
		"GO_DOTENV_ENABLED": "false", "SERVER_PORT": "8081", "APP_RUNTIME_ROLE": "api",
		"SPRING_PROFILES_ACTIVE": "prod", "APP_SECURITY_PRODUCTION_MODE": "true",
		"AUTH_ENABLED": "true", "APP_SECURITY_CSRF_ENABLED": "true", "SESSION_COOKIE_SECURE": "true",
		"AUTH_PASSWORD": "fixture-password-16", "POSTGRES_PASSWORD": "fixture-db",
	} {
		t.Setenv(key, value)
	}
	err := run(context.Background(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err == nil || !strings.Contains(err.Error(), "Go 生产装配尚未迁移") {
		t.Fatalf("必须在监听端口之前拒绝生产配置，error=%v", err)
	}
}
