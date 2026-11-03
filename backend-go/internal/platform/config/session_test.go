package config

import (
	"net/http"
	"testing"
	"time"
)

func TestSessionBindingAndSharedPolicy(t *testing.T) {
	p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"server.servlet.session.timeout": "90", "server.servlet.session.cookie.name": "GO_SESSION", "server.servlet.session.cookie.path": "/api", "server.servlet.session.cookie.domain": "example.test", "server.servlet.session.cookie.http-only": "false", "server.servlet.session.cookie.same-site": "strict"}})
	if err != nil {
		t.Fatal(err)
	}
	s, err := p.Session(false)
	if err != nil || s.Timeout != 90*time.Second || s.Cookie.Name != "GO_SESSION" || s.Cookie.Path != "/api" || s.Cookie.Domain != "example.test" || s.Cookie.HttpOnly || s.Cookie.SameSite != http.SameSiteStrictMode {
		t.Fatal("会话配置未绑定", err)
	}
	if _, err := p.Session(true); err == nil {
		t.Fatal("共享模式不能伪装消费Servlet配置")
	}
	for key, value := range map[string]string{"timeout": "0s", "cookie.name": "bad name", "cookie.path": "relative", "cookie.domain": "a;evil", "cookie.same-site": "private-value", "cookie.max-age": "5m"} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"server.servlet.session." + key: value}})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := p.Session(false); err == nil {
			t.Fatal("未拒绝不支持/非法会话配置", key)
		}
	}
}
