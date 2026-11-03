// personalos 默认离线诊断，显式 --foundation 可启动尚未发布的 P1 基础运行时。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"personal-ai-os/backend-go/internal/app"
	"personal-ai-os/backend-go/internal/platform/config"
)

func main() {
	logger := slog.New(slog.NewTextHandler(os.Stderr, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := runCommand(ctx, os.Args[1:], logger); err != nil {
		logger.Error("Go 迁移进程退出", "error", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, logger *slog.Logger) error {
	env, err := config.LoadEnvironment(os.LookupEnv, "")
	if err != nil {
		return err
	}
	settings, err := env.Config()
	if err != nil {
		return err
	}
	if env.Status() != "disabled" {
		logger.Info("Go 配置来源已检查", "dotenv", env.Status(), "keys", len(env.DotEnvKeys()))
	}
	listener, err := net.Listen("tcp", settings.Address())
	if err != nil {
		return err
	}
	server := &http.Server{
		Handler:           app.NewHandler(settings.Role, logger),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	finished := make(chan error, 1)
	go func() {
		serveErr := server.Serve(listener)
		if errors.Is(serveErr, http.ErrServerClosed) {
			serveErr = nil
		}
		finished <- serveErr
	}()
	logger.Warn("Go 后端业务迁移尚未完成，不能替代 Java 实例", "address", listener.Addr().String(), "role", settings.Role)
	select {
	case err := <-finished:
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		shutdownErr := server.Shutdown(shutdownCtx)
		if shutdownErr != nil {
			_ = server.Close()
		}
		return errors.Join(shutdownErr, <-finished)
	}
}
