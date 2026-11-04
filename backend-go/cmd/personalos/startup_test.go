package main

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/config"
)

func fixtureProcessSystem() map[string]string {
	return map[string]string{"GO_DOTENV_ENABLED": "false", "SERVER_PORT": "18081", "POSTGRES_HOST": "127.0.0.1",
		"POSTGRES_PORT": "1", "POSTGRES_DB": "fixture_db", "POSTGRES_USER": "fixture_user", "POSTGRES_PASSWORD": "fixture-secret"}
}

func TestCommandOptionsAreExplicitAndRedacted(t *testing.T) {
	for _, args := range [][]string{{"--foundation"}, {"--foundation", "--migrations", "relative"}, {"--migrations", "/fixture"}, {"--allow-baseline-v1"}, {"--p5"}, {"--p8"}, {"--unknown=fixture-secret"}, {"fixture-secret"}} {
		_, err := parseCommand(args)
		if err == nil || strings.Contains(err.Error(), "fixture-secret") {
			t.Fatalf("参数边界/脱敏错误: %v", err)
		}
	}
	options, err := parseCommand([]string{"--foundation", "--migrations", "/fixture/sql", "--allow-baseline-v1", "--p5"})
	if err != nil || !options.foundation || !options.allowBaselineV1 || !options.p5 || options.migrations != "/fixture/sql" {
		t.Fatal("显式参数未传递", err)
	}
	options, err = parseCommand(nil)
	if err != nil || options.foundation {
		t.Fatal("默认不能启用连接模式")
	}
}

func TestP8CommandIncludesAllPreviousModules(t *testing.T) {
	o, err := parseCommand([]string{"--foundation", "--migrations", "/fixture/sql", "--p8"})
	if err != nil || !o.p8 || !o.p7 || !o.p6 || !o.p5 {
		t.Fatal(o, err)
	}
}

func TestP8ContainerNetworkIsExplicitAndAuthenticated(t *testing.T) {
	for _, args := range [][]string{{"--container"}, {"--foundation", "--migrations", "/fixture/sql", "--p7", "--container"}} {
		if _, err := parseCommand(args); err == nil {
			t.Fatal("implicit container accepted")
		}
	}
	o, err := parseCommand([]string{"--foundation", "--migrations", "/fixture/sql", "--p8", "--container"})
	if err != nil || !o.container || !o.p8 {
		t.Fatal(o, err)
	}
	for _, override := range []map[string]string{{}, {"AUTH_ENABLED": "true", "AUTH_PASSWORD": "synthetic-password-p8"}, {"APP_SECURITY_CSRF_ENABLED": "true"}} {
		system := fixtureProcessSystem()
		for k, v := range override {
			system[k] = v
		}
		err := runFoundation(context.Background(), o, system, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err == nil || !strings.Contains(err.Error(), "容器网络必须") {
			t.Fatal("container must reject before connection", err)
		}
	}
}

func TestExplicitDatabaseDoesNotUseYAMLConnectionDefaults(t *testing.T) {
	for _, missing := range []string{"SERVER_PORT", "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"} {
		system := fixtureProcessSystem()
		delete(system, missing)
		p, env, err := config.LoadProcessProperties(system, t.TempDir(), nil)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := explicitPostgres(env, p); err == nil {
			t.Fatal("缺少显式变量仍使用 YAML 默认值", missing)
		}
	}
	system := fixtureProcessSystem()
	system["SPRING_DATASOURCE_USERNAME"] = "canonical_user"
	p, env, err := config.LoadProcessProperties(system, t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	o, err := explicitPostgres(env, p)
	if err != nil || o.Host != "127.0.0.1" || o.Port != 1 || o.Database != "fixture_db" || o.Username != "canonical_user" || o.Password != "fixture-secret" || o.SSLMode != "verify-full" {
		t.Fatalf("未使用 canonical 配置或安全 TLS 默认值: %v", err)
	}
	for _, jdbc := range []string{"jdbc:postgresql://localhost/db", "jdbc:postgresql://localhost:5432/db?sslmode=disable", "jdbc:postgresql://user:fixture-secret@localhost:5432/db", "jdbc:postgresql://localhost:5432/a/b", "fixture-secret"} {
		p, env, err := config.LoadProcessProperties(system, t.TempDir(), map[string]string{"spring.datasource.url": jdbc})
		if err != nil {
			t.Fatal(err)
		}
		_, err = explicitPostgres(env, p)
		if err == nil || strings.Contains(err.Error(), "fixture-secret") {
			t.Fatal("未拒绝或泄露未支持地址")
		}
	}
}

func TestFoundationRejectsBeforeConnecting(t *testing.T) {
	for key, value := range map[string]string{
		"SPRING_PROFILES_ACTIVE": "prod", "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT": "0",
		"APP_AUTH_MAXFAILURES": "0", "SERVER_PORT": "0", "SPRING_CONFIG_LOCATION": "fixture-secret",
	} {
		system := fixtureProcessSystem()
		system[key] = value
		err := runFoundation(context.Background(), commandOptions{foundation: true, migrations: t.TempDir()}, system, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err == nil || strings.Contains(err.Error(), "fixture-secret") || strings.Contains(err.Error(), "数据库健康检查") {
			t.Fatalf("配置必须在连接前脱敏拒绝: %s %v", key, err)
		}
	}
}
