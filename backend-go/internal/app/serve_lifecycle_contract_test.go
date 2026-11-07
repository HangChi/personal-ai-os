package app

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func defaultServingFoundation(t *testing.T, monitoring bool) *Foundation {
	t.Helper()
	values := map[string]string{"app.runtime.role": "api", "app.auth.enabled": "false"}
	if monitoring {
		values["spring.profiles.active"] = "monitoring"
	}
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if err != nil {
		t.Fatal(err)
	}
	f, err := OpenFoundation(context.Background(), properties, FoundationOptions{Connector: foundationContractConnector(t, false), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := f.Close(ctx); err != nil {
			t.Error(err)
		}
	})
	return f
}

func defaultListener(t *testing.T) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = l.Close() })
	return l
}

func TestDefaultServeRunsBusinessAndManagementThenClosesOwnedResources(t *testing.T) {
	for _, monitoring := range []bool{false, true} {
		t.Run(map[bool]string{false: "business", true: "separate-management"}[monitoring], func(t *testing.T) {
			f := defaultServingFoundation(t, monitoring)
			business := defaultListener(t)
			var management net.Listener
			if monitoring {
				management = defaultListener(t)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { done <- f.Serve(ctx, business, management, time.Second) }()
			client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 3 * time.Second}
			defer client.CloseIdleConnections()
			check := func(listener net.Listener, path, want string, status int) {
				t.Helper()
				response, err := client.Get("http://" + listener.Addr().String() + path)
				if err != nil {
					t.Fatal(err)
				}
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				if err != nil || response.StatusCode != status || !strings.Contains(string(body), want) {
					t.Fatal(response.StatusCode, string(body), err)
				}
			}
			check(business, "/api/v1/health", "UP", 200)
			if monitoring {
				if f.ManagementHandler() == nil {
					t.Fatal("monitoring handler not assembled")
				}
				check(management, "/actuator/health", `"status":"UP"`, 200)
				check(management, "/actuator/prometheus", "personalos_go_database_connections", 200)
				check(management, "/api/v1/health", "", 403)
			} else if f.ManagementHandler() != nil {
				t.Fatal("management enabled without profile")
			}
			// A live foundation cannot own a second pair of servers.
			extra := defaultListener(t)
			var extraManagement net.Listener
			if monitoring {
				extraManagement = defaultListener(t)
			}
			if err := f.Serve(context.Background(), extra, extraManagement, time.Second); err == nil {
				t.Fatal("foundation served twice")
			}
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(4 * time.Second):
				t.Fatal("servers did not shut down")
			}
			if err := f.Pool().Ping(context.Background()); err == nil {
				t.Fatal("server shutdown left owned pool open")
			}
			for _, listener := range []net.Listener{business, management} {
				if listener == nil {
					continue
				}
				rebound, err := net.Listen("tcp", listener.Addr().String())
				if err != nil {
					t.Fatal("server retained listener", err)
				}
				rebound.Close()
			}
		})
	}
}

func TestDefaultServeListenerFailureClosesFoundationAndSanitizesError(t *testing.T) {
	f := defaultServingFoundation(t, true)
	business, management := defaultListener(t), defaultListener(t)
	if _, ok := loopbackListener(business); !ok {
		t.Fatal("loopback listener rejected")
	}
	if err := business.Close(); err != nil {
		t.Fatal(err)
	}
	err := f.Serve(context.Background(), business, management, time.Second)
	if err == nil || err.Error() != "基础HTTP监听失败" {
		t.Fatal("listener failure exposed transport detail", err)
	}
	if err := f.Pool().Ping(context.Background()); err == nil {
		t.Fatal("failed server did not close foundation")
	}
}
