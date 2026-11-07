package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"personal-ai-os/backend-go/internal/platform/config"
	"testing"
)

func TestOfflineListenerShutdownAndOccupiedPort(t *testing.T) {
	t.Chdir(t.TempDir())
	for k, v := range map[string]string{"GO_DOTENV_ENABLED": "false", "SPRING_PROFILES_ACTIVE": "dev", "APP_SECURITY_PRODUCTION_MODE": "false", "AUTH_ENABLED": "false"} {
		t.Setenv(k, v)
	}
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	t.Setenv("SERVER_PORT", fmt.Sprint(port))
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if e := run(context.Background(), logger); e == nil {
		t.Fatal("occupied port accepted")
	}
	listener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if e := run(ctx, logger); e != nil {
		t.Fatal(e)
	}
	t.Setenv("GO_DOTENV_ENABLED", "true")
	if e := os.WriteFile(".env", []byte("SERVER_PORT=1"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := run(ctx, logger); e != nil {
		t.Fatal(e)
	}
	t.Setenv("GO_DOTENV_ENABLED", "invalid")
	if e := run(ctx, logger); e == nil {
		t.Fatal("invalid env")
	}
	if _, e := parseCommand([]string{"--help"}); !errors.Is(e, flag.ErrHelp) {
		t.Fatal(e)
	}
	if e := runCommand(ctx, []string{"--help"}, logger); e != nil {
		t.Fatal(e)
	}
	if e := runCommand(ctx, []string{"--invalid"}, logger); e == nil {
		t.Fatal("invalid command")
	}
}
func TestDedicatedRedisAndFoundationPreflightBoundaries(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if e := runFoundation(nil, commandOptions{}, nil, logger); e == nil {
		t.Fatal("nil context")
	}
	if e := runFoundation(context.Background(), commandOptions{}, nil, nil); e == nil {
		t.Fatal("nil logger")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if e := runFoundation(ctx, commandOptions{}, nil, logger); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	for _, cert := range []string{"", "relative", filepath.Join(t.TempDir(), "missing.pem"), filepath.Join(t.TempDir(), "invalid.pem")} {
		if filepath.IsAbs(cert) && filepath.Base(cert) == "invalid.pem" {
			os.WriteFile(cert, []byte("invalid certificate"), 0600)
		}
		system := fixtureProcessSystem()
		system["GO_REDIS_TEST_ROOT_CERT"] = cert
		p, env, e := config.LoadProcessProperties(system, t.TempDir(), map[string]string{"app.redis.uri": "rediss://127.0.0.1:1"})
		if e != nil {
			t.Fatal(e)
		}
		client, e := dedicatedRedis(p, env, "app.redis", "GO_REDIS_TEST_ROOT_CERT")
		if client != nil {
			client.Close()
		}
		if e == nil {
			t.Fatal("invalid certificate accepted")
		}
	}
	for _, key := range []string{"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"} {
		p, env, e := config.LoadProcessProperties(fixtureProcessSystem(), t.TempDir(), map[string]string{key: "$" + "{not.present}"})
		if e != nil {
			t.Fatal(e)
		}
		if _, e := explicitPostgres(env, p); e == nil {
			t.Fatal("unresolved property accepted")
		}
	}

}
