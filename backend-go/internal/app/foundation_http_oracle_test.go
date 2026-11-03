package app

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

type foundationHTTPFixture struct {
	Auth, CSRF, Monitoring bool
	Request                struct {
		Name, Method, Path, ContentType, Accept, Body string
		CSRF, BadCSRF, Management, DatabaseDown       bool
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
	if len(fixture.Cases) != 200 {
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
