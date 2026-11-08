package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestProcessPropertiesExplicitSources(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte("SPRING_PROFILES_ACTIVE=worker-review\nAUTH_USERNAME=dotenv-user\n"), 0600); err != nil {
		t.Fatal(err)
	}
	system := map[string]string{"GO_DOTENV_ENABLED": "false"}
	p, env, err := LoadProcessProperties(system, dir, nil)
	if err != nil || env.Status() != "disabled" {
		t.Fatal("默认不得读取文件", err)
	}
	f, err := p.Foundation()
	if err != nil || f.Auth.Username != "admin" {
		t.Fatal("关闭时仍读取了 dotenv", err)
	}
	system["GO_DOTENV_ENABLED"] = "true"
	system["AUTH_USERNAME"] = "system-user"
	p, env, err = LoadProcessProperties(system, dir, nil)
	if err != nil || env.Status() != "loaded" {
		t.Fatal("显式读取失败", err)
	}
	system["AUTH_USERNAME"] = "mutated"
	f, err = p.Foundation()
	if err != nil || f.Role != Combined || f.Auth.Username != "system-user" {
		t.Fatal("来源优先级、晚激活或副本不正确", err)
	}
	if name, _ := env.Lookup("AUTH_USERNAME"); name != "system-user" {
		t.Fatal("环境快照被调用者修改")
	}
}

func TestProcessUploadLimitsMatchCurrentDefaults(t *testing.T) {
	p, _, err := LoadProcessProperties(nil, t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	value, found, err := p.Get("app.knowledge.max-file-size-bytes")
	if err != nil || !found || value != "10485760" {
		t.Fatalf("knowledge upload limit: %q, found=%v, err=%v", value, found, err)
	}
	multipart, err := p.Multipart()
	if err != nil || multipart.MaxFileBytes != 12<<20 || multipart.MaxRequestBytes != 14<<20 {
		t.Fatalf("multipart upload limits: %+v, err=%v", multipart, err)
	}

	p, _, err = LoadProcessProperties(map[string]string{"AI_KNOWLEDGE_MAX_FILE_SIZE": "6291456"}, t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	value, found, err = p.Get("app.knowledge.max-file-size-bytes")
	if err != nil || !found || value != "6291456" {
		t.Fatalf("knowledge upload override: %q, found=%v, err=%v", value, found, err)
	}
}

func TestProcessRuntimeProfilesAndBounds(t *testing.T) {
	for _, test := range []struct {
		profiles string
		maximum  int
	}{{"", 10}, {"runtime-api", 10}, {"worker-index", 4}, {"worker-review", 4}, {"worker-delivery", 4}, {"monitoring,worker-index", 4}} {
		p, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{"SPRING_PROFILES_ACTIVE": test.profiles, "SERVER_PORT": "18081"}})
		if err != nil {
			t.Fatal(err)
		}
		r, err := p.ProcessRuntime()
		if err != nil || r.Port != 18081 || r.ManagementPort != 9091 || r.PoolMaximum != test.maximum || r.PoolConnectionTimeout != 5*time.Second || r.ShutdownTimeout != 30*time.Second {
			t.Fatalf("实际进程配置错误: %+v %v", r, err)
		}
	}
	for key, values := range map[string][]string{
		"server.port":            {"0", "65536", "fixture-private-value"},
		"management.server.port": {"-1", "65536"},
		"spring.datasource.hikari.maximum-pool-size":  {"0", "-1", "2147483648"},
		"spring.datasource.hikari.connection-timeout": {"0", "249", "1s", "9223372036854775807"},
		"spring.lifecycle.timeout-per-shutdown-phase": {"0", "-1s", "invalid"},
	} {
		for _, value := range values {
			p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{key: value}})
			if err != nil {
				t.Fatal(err)
			}
			if _, err = p.ProcessRuntime(); err == nil {
				t.Fatalf("未拒绝 %s", key)
			}
		}
	}
}
