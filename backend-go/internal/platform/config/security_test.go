package config

import (
	"fmt"
	"strings"
	"testing"
)

func validProduction() Security {
	return Security{Production: true, AuthEnabled: true, CSRFEnabled: true, CookieSecure: true,
		authPassword: "fixture-password-16", databasePassword: "fixture-database-password"}
}

// 期望文案和顺序直接来自固定 Java ProductionSecurityValidator。
func TestJavaProductionSecurityValidation(t *testing.T) {
	for _, test := range []struct {
		name   string
		change func(*Security)
		want   string
	}{
		{"localMode", func(s *Security) { *s = Security{} }, ""},
		{"valid", func(*Security) {}, ""},
		{"auth", func(s *Security) { s.AuthEnabled = false; s.CSRFEnabled = false }, "生产配置必须启用 AUTH_ENABLED=true"},
		{"csrf", func(s *Security) { s.CSRFEnabled = false; s.CookieSecure = false }, "生产配置必须启用 app.security.csrf-enabled=true"},
		{"cookie", func(s *Security) { s.CookieSecure = false; s.authPassword = "" }, "生产配置必须启用 SESSION_COOKIE_SECURE=true"},
		{"blankAuth", func(s *Security) { s.authPassword = strings.Repeat("\u3000", 8) }, "AUTH_PASSWORD 必须为 16-72 字节且不能使用示例密码"},
		{"shortAuth", func(s *Security) { s.authPassword = "fixture-only" }, "AUTH_PASSWORD 必须为 16-72 字节且不能使用示例密码"},
		{"blankDatabase", func(s *Security) { s.databasePassword = "\u001c \t" }, "生产配置必须设置非默认 POSTGRES_PASSWORD"},
		{"defaultDatabase", func(s *Security) { s.databasePassword = "personalos" }, "生产配置必须设置非默认 POSTGRES_PASSWORD"},
		{"postgresDatabase", func(s *Security) { s.databasePassword = "postgres" }, "生产配置必须设置非默认 POSTGRES_PASSWORD"},
		{"databaseExactCase", func(s *Security) { s.databasePassword = "Postgres" }, ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			security := validProduction()
			test.change(&security)
			err := security.ValidateProduction()
			if test.want == "" {
				if err != nil {
					t.Fatal(err)
				}
			} else if err == nil || err.Error() != test.want {
				t.Fatalf("生产守卫错误，want=%q, got=%v", test.want, err)
			}
		})
	}
}

func TestProductionPasswordUsesUTF8BytesAndJavaBlank(t *testing.T) {
	for _, test := range []struct {
		password string
		valid    bool
	}{
		{strings.Repeat("a", 15), false}, {strings.Repeat("a", 16), true},
		{strings.Repeat("a", 72), true}, {strings.Repeat("a", 73), false},
		{strings.Repeat("密", 5), false}, {strings.Repeat("密", 6), true},
		{strings.Repeat("密", 24), true}, {strings.Repeat("密", 25), false},
		{strings.Repeat("😀", 4), true}, {strings.Repeat("😀", 18), true}, {strings.Repeat("😀", 19), false},
		{strings.Repeat(" ", 16), false}, {strings.Repeat("\u001c", 16), false},
		{strings.Repeat("\u00a0", 8), true}, {strings.Repeat("\u0085", 8), true},
	} {
		security := validProduction()
		security.authPassword = test.password
		if (security.ValidateProduction() == nil) != test.valid {
			t.Errorf("密码长度 %d 字节的 Java 校验结果不一致", len(test.password))
		}
	}
}

func TestSecurityDefaultsProfilesOverrideAndRedaction(t *testing.T) {
	security, err := LoadSecurity(environment(nil), false)
	if err != nil || security.Production || security.AuthEnabled || security.CSRFEnabled || security.CookieSecure {
		t.Fatalf("本地默认值错误：%v，%v", security, err)
	}
	security, err = LoadSecurity(environment(map[string]string{"AUTH_PASSWORD": "fixture-private-auth", "POSTGRES_PASSWORD": "fixture-private-db"}), true)
	if err != nil || security.ValidateProduction() != nil {
		t.Fatalf("prod 应启用四个安全开关：%v，%v", security, err)
	}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, security), "fixture-private") {
			t.Fatal("格式化安全配置不得泄露密码")
		}
	}
	security, err = LoadSecurity(environment(map[string]string{"AUTH_ENABLED": " false "}), true)
	if err != nil || security.AuthEnabled {
		t.Fatal("显式环境值应覆盖 profile 默认值")
	}
	if err = security.ValidateProduction(); err == nil || err.Error() != "生产配置必须启用 AUTH_ENABLED=true" {
		t.Fatalf("不能因 profile 回落掩盖危险配置：%v", err)
	}
	if _, err := LoadSecurity(nil, false); err == nil {
		t.Fatal("nil 来源应报错")
	}
}

func TestBootstrapRejectsUnimplementedSecurityBeforeListening(t *testing.T) {
	for _, variables := range []map[string]string{
		{"SPRING_PROFILES_ACTIVE": "prod", "AUTH_PASSWORD": "fixture-password-16", "POSTGRES_PASSWORD": "fixture-db"},
		{"SPRING_PROFILES_ACTIVE": "prod", "APP_SECURITY_PRODUCTION_MODE": "false", "AUTH_ENABLED": "false", "APP_SECURITY_CSRF_ENABLED": "false"},
		{"APP_SECURITY_PRODUCTION_MODE": "true", "AUTH_ENABLED": "true", "APP_SECURITY_CSRF_ENABLED": "true", "SESSION_COOKIE_SECURE": "true", "AUTH_PASSWORD": "fixture-password-16", "POSTGRES_PASSWORD": "fixture-db"},
		{"AUTH_ENABLED": "true"},
		{"APP_SECURITY_CSRF_ENABLED": "true"},
	} {
		if _, err := Load(environment(variables)); err == nil || !strings.Contains(err.Error(), "尚未迁移") {
			t.Fatalf("未实现的安全能力必须阻止启动：%v", err)
		}
	}
	for _, key := range []string{"APP_SECURITY_PRODUCTION_MODE", "AUTH_ENABLED", "APP_SECURITY_CSRF_ENABLED", "SESSION_COOKIE_SECURE"} {
		_, err := Load(environment(map[string]string{key: "fixture-private-value"}))
		if err == nil || strings.Contains(err.Error(), "fixture-private-value") {
			t.Fatalf("无效安全配置不能静默关闭或回显原值：%v", err)
		}
	}
}

func TestProfileRoleOrderAndEnvironmentOverride(t *testing.T) {
	for _, test := range []struct {
		variables map[string]string
		want      Role
	}{
		{map[string]string{"SPRING_PROFILES_ACTIVE": "monitoring,runtime-api"}, API},
		{map[string]string{"SPRING_PROFILES_ACTIVE": "worker-index"}, Index},
		{map[string]string{"SPRING_PROFILES_ACTIVE": "worker-review"}, Review},
		{map[string]string{"SPRING_PROFILES_DEFAULT": "worker-delivery"}, Delivery},
		{map[string]string{"SPRING_PROFILES_ACTIVE": "worker-index, worker-review, ,worker-delivery"}, Delivery},
		{map[string]string{"SPRING_PROFILES_ACTIVE": "worker-index", "APP_RUNTIME_ROLE": "api"}, API},
		{map[string]string{"SPRING_PROFILES_ACTIVE": "monitoring", "SPRING_PROFILES_DEFAULT": "prod"}, Combined},
	} {
		settings, err := Load(environment(test.variables))
		if err != nil || settings.Role != test.want {
			t.Errorf("profile 角色错误，want=%s, got=%s, error=%v", test.want, settings.Role, err)
		}
	}
	if _, err := Load(nil); err == nil {
		t.Fatal("nil 来源应报错")
	}
}
