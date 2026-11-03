package config

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestFoundationPropertiesSources(t *testing.T) {
	for _, test := range []struct {
		name           string
		system, dotenv map[string]string
		role           Role
		shared, csrf   bool
	}{
		{"default", nil, nil, Combined, false, false},
		{"worker profile", map[string]string{"SPRING_PROFILES_ACTIVE": "worker-index"}, nil, Index, false, false},
		{"late dotenv profile", nil, map[string]string{"SPRING_PROFILES_ACTIVE": "worker-index"}, Combined, false, false},
		{"explicit role", map[string]string{"APP_RUNTIME_ROLE": "delivery", "REDIS_SESSION_ENABLED": "true", "APP_SECURITY_CSRF_ENABLED": "true"}, nil, Delivery, true, true},
		{"conditional exact true", map[string]string{"REDIS_SESSION_ENABLED": "on"}, nil, Combined, false, false},
		{"conditional ignores case", map[string]string{"REDIS_SESSION_ENABLED": "TRUE"}, nil, Combined, true, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			p, err := LoadBaselineProperties(PropertyOptions{System: test.system, DotEnv: test.dotenv})
			if err != nil {
				t.Fatal(err)
			}
			got, err := p.Foundation()
			if err != nil {
				t.Fatal(err)
			}
			if got.Role != test.role || got.SharedSession != test.shared || got.Security.CSRFEnabled != test.csrf || got.ModelConcurrency != 4 || got.Auth.Username != "admin" || got.RateLimit.Capacity != 60 {
				t.Fatalf("基础配置未正确组合: %+v", got)
			}
		})
	}
}

func TestFoundationPropertiesRejectAndRedact(t *testing.T) {
	for _, values := range []map[string]string{
		{"app.runtime.role": "unknown"}, {"app.model-max-concurrent-requests": "0"},
		{"app.auth.enabled": "maybe"}, {"app.security.csrf-enabled": ""},
		{"app.auth.max-failures": "invalid"}, {"app.security.production-mode": "true"},
	} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: values})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := p.Foundation(); err == nil {
			t.Fatal("非法或生产配置不能进入未验收装配")
		}
	}
	p, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{"AUTH_PASSWORD": "fixture-secret", "AUTH_USERNAME": "fixture-user"}})
	if err != nil {
		t.Fatal(err)
	}
	got, err := p.Foundation()
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{fmt.Sprintf("%+v", got), fmt.Sprintf("%#v", got), string(encoded)} {
		if strings.Contains(s, "fixture-") {
			t.Fatal("基础配置格式化泄露凭据")
		}
	}
	if *got.Auth.Password() != "fixture-secret" {
		t.Fatal("凭据未显式传递给认证")
	}
	var empty *Properties
	if _, err := empty.Foundation(); err == nil {
		t.Fatal("nil来源必须拒绝")
	}
}

func TestFoundationMonitoringProfile(t *testing.T) {
	for _, source := range []map[string]string{{}, {"SPRING_PROFILES_ACTIVE": "monitoring"}, {"SPRING_PROFILES_DEFAULT": "monitoring"}} {
		p, err := LoadBaselineProperties(PropertyOptions{System: source})
		if err != nil {
			t.Fatal(err)
		}
		got, err := p.Foundation()
		if err != nil {
			t.Fatal(err)
		}
		if got.Monitoring != (len(source) > 0) {
			t.Fatal("独立管理链未按有效profile装配")
		}
	}
}
