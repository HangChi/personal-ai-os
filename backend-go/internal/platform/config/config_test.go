package config

import "testing"

func environment(values map[string]string) func(string) (string, bool) {
	return func(key string) (string, bool) { value, found := values[key]; return value, found }
}

func TestBootstrapDefaultsStayIsolated(t *testing.T) {
	settings, err := Load(environment(nil))
	if err != nil || settings.Port != 8081 || settings.Role != Combined || settings.Address() != "127.0.0.1:8081" {
		t.Fatalf("迁移进程默认配置不安全：%+v，%v", settings, err)
	}
	settings, err = Load(environment(map[string]string{"SERVER_PORT": "18081", "APP_RUNTIME_ROLE": "index"}))
	if err != nil || settings.Address() != "127.0.0.1:18081" || !settings.Role.Worker() {
		t.Fatalf("覆盖配置错误：%+v，%v", settings, err)
	}
	for _, port := range []string{"", "0", "-1", "65536", "abc"} {
		if _, err := Load(environment(map[string]string{"SERVER_PORT": port})); err == nil {
			t.Errorf("无效端口 %q 应阻止启动", port)
		}
	}
}

func TestJavaRuntimeRoleMatrix(t *testing.T) {
	for _, role := range []Role{Combined, API, Index, Review, Delivery} {
		parsed, err := ParseRole(string(role))
		if err != nil || parsed != role {
			t.Fatalf("角色解析失败：%v，%v", role, err)
		}
		for _, task := range []string{"api", "index", "review", "delivery", "unknown"} {
			want := role == Combined || string(role) == task
			if role.Allows(task) != want {
				t.Errorf("角色 %s 的 %s 权限错误", role, task)
			}
		}
		if role.Worker() != (role != Combined && role != API) {
			t.Errorf("角色 %s 的 Worker 判定错误", role)
		}
	}
	for _, invalid := range []string{"", "API", " api ", "all"} {
		if _, err := ParseRole(invalid); err == nil {
			t.Errorf("无效角色 %q 应阻止启动", invalid)
		}
	}
}
