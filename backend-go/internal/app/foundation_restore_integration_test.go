//go:build integration

package app

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func TestFoundationStartsFromRestoredBackup(t *testing.T) {
	if os.Getenv("GO_TEST_BACKUP") != "disposable" {
		t.Skip("仅使用runner新恢复的合成备份")
	}
	for _, role := range []string{"combined", "api", "index", "review", "delivery"} {
		t.Run(role, func(t *testing.T) {
			p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.runtime.role": role, "app.rate-limit.enabled": "false"}})
			if err != nil {
				t.Fatal(err)
			}
			f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppDatabaseConnector(t, "go-restored-foundation", true), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				if err := f.Close(ctx); err != nil {
					t.Error(err)
				}
			})
			if f.History().CurrentVersion != 42 {
				t.Fatal("恢复历史未被装配")
			}
			w := httptest.NewRecorder()
			f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
			want := 200
			if role != "api" && role != "combined" {
				want = 404
			}
			if w.Code != want {
				t.Fatal("恢复库HTTP未启动", w.Code)
			}
			lease, err := f.ModelGate().TryAcquire(context.Background())
			if err != nil || lease == nil {
				t.Fatal("恢复库协调失败", err)
			}
			lease.Release(context.Background())
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := f.Close(ctx); err != nil {
				t.Fatal(err)
			}
			if f.Pool().Stats().OpenConnections != 0 {
				t.Fatal("恢复库关闭泄漏")
			}
		})
	}
}
