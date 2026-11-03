package app

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"reflect"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
)

type foundationHTTPFixture struct {
	Auth, CSRF, Monitoring bool
	Request                struct {
		Name, Method, Path, ContentType, Accept, Body                 string
		CSRF, BadCSRF, Management, DatabaseDown, Login, MetricExpired bool
	}
	Response struct {
		Status             int
		ContentType, Allow string
		Body               any
	}
}

func readFoundationHTTPFixture(t *testing.T) []foundationHTTPFixture {
	t.Helper()
	data, err := os.ReadFile("testdata/java-foundation-http.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct{ Cases []foundationHTTPFixture }
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) != 292 {
		t.Fatal("真实 HTTP 场景数量改变，需审查")
	}
	return fixture.Cases
}

func normalizeFoundationBody(t *testing.T, data []byte) any {
	t.Helper()
	if len(data) == 0 {
		return ""
	}
	var result any
	if err := json.Unmarshal(data, &result); err != nil {
		return string(data)
	}
	if body, ok := result.(map[string]any); ok {
		if value, found := body["timestamp"]; found {
			if _, err := time.Parse(time.RFC3339Nano, value.(string)); err != nil {
				t.Fatal("时间戳无效", err)
			}
			body["timestamp"] = "<generated>"
		}
		if payload, ok := body["data"].(map[string]any); ok && payload["token"] != nil {
			payload["token"] = "<generated>"
		}
	}
	return result
}

func normalizedAllow(value string) string {
	parts := strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ' ' })
	sort.Strings(parts)
	return strings.Join(parts, ",")
}

func compareFoundationResponse(t *testing.T, client *http.Client, server string, test foundationHTTPFixture) {
	t.Helper()
	r, err := http.NewRequest(test.Request.Method, server+test.Request.Path, strings.NewReader(test.Request.Body))
	if err != nil {
		t.Fatal(err)
	}
	if test.Request.ContentType != "" {
		r.Header.Set("Content-Type", test.Request.ContentType)
	}
	if test.Request.Accept != "" {
		r.Header.Set("Accept", test.Request.Accept)
	}
	if test.Request.CSRF {
		response, err := client.Get(server + "/api/v1/auth/csrf")
		if err != nil {
			t.Fatal(err)
		}
		var token struct{ Data struct{ Token *string } }
		err = json.NewDecoder(response.Body).Decode(&token)
		response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		if token.Data.Token != nil {
			r.Header.Set("X-CSRF-TOKEN", *token.Data.Token)
		}
	}
	if test.Request.BadCSRF {
		r.Header.Set("X-CSRF-TOKEN", "fixture-invalid-token")
	}
	if test.Request.Login {
		login, err := http.NewRequest("POST", server+"/api/v1/auth/login", strings.NewReader(`{"username":"admin","password":"fixture-password"}`))
		if err != nil {
			t.Fatal(err)
		}
		login.Header.Set("Content-Type", "application/json")
		if token := r.Header.Get("X-CSRF-TOKEN"); token != "" {
			login.Header.Set("X-CSRF-TOKEN", token)
		}
		response, err := client.Do(login)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != 200 {
			t.Fatal("合成登录失败")
		}
	}
	response, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	got := normalizeFoundationBody(t, data)
	if response.StatusCode != test.Response.Status || response.Header.Get("Content-Type") != test.Response.ContentType || normalizedAllow(response.Header.Get("Allow")) != normalizedAllow(test.Response.Allow) || !reflect.DeepEqual(got, test.Response.Body) {
		t.Errorf("与真实Servlet不同: status=%d/%d type=%q/%q allow=%q/%q body=%v / %v", response.StatusCode, test.Response.Status, response.Header.Get("Content-Type"), test.Response.ContentType, response.Header.Get("Allow"), test.Response.Allow, got, test.Response.Body)
	}
}

func TestFoundationActuatorRealServletOracle(t *testing.T) {
	fixtures := readFoundationHTTPFixture(t)
	for _, mode := range []struct{ auth, csrf, monitoring bool }{{false, false, false}, {true, false, false}, {true, true, false}, {true, true, true}} {
		properties := config.ActuatorProperties{Health: true, Info: !mode.monitoring, Metrics: !mode.monitoring, Prometheus: mode.monitoring, HealthDetails: "when_authorized", InfoEnvironment: true, InfoName: "fixture", InfoVersion: "test", SSL: true, Ping: true, Probes: true}
		var down atomic.Bool
		clock := time.Unix(1_000, 0)
		metrics := &foundationMetrics{clock: func() time.Time { return clock }}
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))
		actuator := newActuatorHandler(properties, func(context.Context) actuatorHealth {
			state := defaultActuatorHealth(!down.Load(), properties)
			db := state.Components["db"]
			db.Details = map[string]any{"database": "fixture"}
			state.Components["db"] = db
			return state
		}, metrics, logger)
		var businessActuator http.Handler = actuator
		if mode.monitoring {
			businessActuator = nil
		}
		server := httptest.NewServer(fixtureFoundationHandler(t, config.API, mode.auth, mode.csrf, &fixtureLimiter{allowed: true}, nil, nil, businessActuator))
		management := httptest.NewServer(newManagementChain(actuator, logger))
		transport := &http.Transport{Proxy: nil}
		for _, test := range fixtures {
			if test.Auth != mode.auth || test.CSRF != mode.csrf || test.Monitoring != mode.monitoring || !test.Request.Management && !strings.HasPrefix(test.Request.Path, "/actuator/") {
				continue
			}
			t.Run(strings.Join([]string{map[bool]string{true: "auth", false: "anonymous"}[mode.auth], map[bool]string{true: "csrf", false: "plain"}[mode.csrf], map[bool]string{true: "monitoring", false: "business"}[mode.monitoring], test.Request.Name}, "/"), func(t *testing.T) {
				down.Store(test.Request.DatabaseDown)
				metrics.mu.Lock()
				metrics.counts, metrics.seconds, metrics.maxima, metrics.redisErrors = [2]uint64{}, [2]float64{}, [2]metricWindowMax{}, 0
				metrics.mu.Unlock()
				metrics.RecordAcquisition(ratelimit.Postgres, 10*time.Millisecond, false)
				metrics.RecordAcquisition(ratelimit.Postgres, 20*time.Millisecond, false)
				metrics.RecordAcquisition(ratelimit.Redis, 30*time.Millisecond, false)
				metrics.mu.Lock()
				metrics.redisErrors = 2
				metrics.mu.Unlock()
				if test.Request.MetricExpired {
					clock = clock.Add(3 * time.Minute)
				}
				jar, _ := cookiejar.New(nil)
				client := &http.Client{Transport: transport, Jar: jar, Timeout: 5 * time.Second}
				origin := server.URL
				if mode.monitoring && test.Request.Management {
					origin = management.URL
				}
				compareFoundationResponse(t, client, origin, test)
			})
		}
		transport.CloseIdleConnections()
		server.Close()
		management.Close()
	}
}

func TestFoundationAuthRealServletOracle(t *testing.T) {
	fixtures := readFoundationHTTPFixture(t)
	for _, mode := range []struct{ auth, csrf, monitoring bool }{{false, false, false}, {true, false, false}, {true, true, false}, {true, true, true}} {
		h := fixtureFoundationHandler(t, config.API, mode.auth, mode.csrf, &fixtureLimiter{allowed: true}, nil, nil)
		server := httptest.NewServer(h)
		transport := &http.Transport{Proxy: nil}
		for _, test := range fixtures {
			if test.Auth != mode.auth || test.CSRF != mode.csrf || test.Monitoring != mode.monitoring || test.Request.Management || strings.HasPrefix(test.Request.Path, "/actuator/") {
				continue
			}
			t.Run(strings.Join([]string{map[bool]string{true: "auth", false: "anonymous"}[mode.auth], map[bool]string{true: "csrf", false: "plain"}[mode.csrf], map[bool]string{true: "monitoring", false: "business"}[mode.monitoring], test.Request.Name}, "/"), func(t *testing.T) {
				jar, _ := cookiejar.New(nil)
				client := &http.Client{Transport: transport, Jar: jar, Timeout: 5 * time.Second}
				compareFoundationResponse(t, client, server.URL, test)
			})
		}
		transport.CloseIdleConnections()
		server.Close()
	}
}
