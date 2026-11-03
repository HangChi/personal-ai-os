package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestJavaCookieSourceResolutionAndProductionGuard(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "specs", "033-go-rewrite", "config-inventory.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Scenario string `json:"scenario"`
			Secure   bool   `json:"guard_cookie_secure"`
			Guard    string `json:"production_guard"`
		} `json:"cookie_resolution_cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) != 4 {
		t.Fatal("缺少真实Spring来源对照")
	}
	for _, test := range fixture.Cases {
		t.Run(test.Scenario, func(t *testing.T) {
			variables := map[string]string{"AUTH_PASSWORD": "fixture-password-16", "POSTGRES_PASSWORD": "fixture-non-default-database"}
			if test.Scenario != "local-default" {
				variables["SESSION_COOKIE_SECURE"] = "true"
			}
			if test.Scenario == "prod-canonical-env" {
				variables["SERVER_SERVLET_SESSION_COOKIE_SECURE"] = "true"
			}
			security, err := LoadSecurity(environment(variables), strings.HasPrefix(test.Scenario, "prod"))
			if err != nil {
				t.Fatal(err)
			}
			if security.CookieSecure != test.Secure {
				t.Fatal("Go安全读取和真实Java来源不一致", security)
			}
			err = security.ValidateProduction()
			if test.Guard == "accepted" {
				if err != nil {
					t.Fatal(err)
				}
			} else if err == nil || err.Error() != test.Guard {
				t.Fatal("生产守卫与真实Java不一致", err)
			}
		})
	}
}

func TestCookieCanonicalAliasesRespectSourcePriority(t *testing.T) {
	for _, key := range []string{"server.servlet.session.cookie.secure", "server_servlet_session_cookie_secure", "SERVER.SERVLET.SESSION.COOKIE.SECURE", "SERVER_SERVLET_SESSION_COOKIE_SECURE"} {
		security, err := LoadSecurity(environment(map[string]string{key: "true"}), false)
		if err != nil || !security.CookieSecure {
			t.Fatal("SystemEnvironment别名丢失", key, err)
		}
	}
	dotenv, err := ParseDotEnv([]byte("server.servlet.session.cookie.secure=false\nSERVER_SERVLET_SESSION_COOKIE_SECURE=false\n"))
	if err != nil {
		t.Fatal(err)
	}
	env := Environment{system: environment(map[string]string{"SERVER_SERVLET_SESSION_COOKIE_SECURE": "true"}), dotenv: dotenv}
	security, err := loadSecurity(env.Lookup, env.system, true)
	if err != nil || !security.CookieSecure {
		t.Fatal("低优先级精确键不能覆盖高优先级别名", err)
	}
	env.system = environment(nil)
	security, err = loadSecurity(env.Lookup, env.system, true)
	if err != nil || security.CookieSecure {
		t.Fatal("prod YAML没有定义canonical Cookie，不能伪造true", err)
	}
	if _, err := LoadSecurity(environment(map[string]string{"SESSION_COOKIE_SECURE": "unused-invalid-value"}), false); err != nil {
		t.Fatal("未被Java消费的别名不能冒充canonical值", err)
	}
}
