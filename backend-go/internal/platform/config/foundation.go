package config

import (
	"errors"
	"slices"
	"strings"
)

// FoundationProperties只包含P1实际装配的配置；不代表后续业务record已接线。
type FoundationProperties struct {
	Role                            Role
	Auth                            AuthProperties
	RateLimit                       RateLimitProperties
	App                             AppProperties
	Security                        Security
	ModelConcurrency                int
	SharedSession, RedisRateLimit   bool
	Monitoring                      bool
	SessionNamespace, RateNamespace string
}

func (FoundationProperties) String() string               { return "FoundationProperties{redacted}" }
func (p FoundationProperties) GoString() string           { return p.String() }
func (FoundationProperties) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

// Foundation显式解析固定基线来源并交给已有record Binder；不读宿主来源或创建连接。
// URI/连接池等资源仍由调用方显式提供；此处不自动发现任意服务。
func (p *Properties) Foundation() (FoundationProperties, error) {
	if p == nil {
		return FoundationProperties{}, errors.New("基础装配配置来源不能为空")
	}
	values := make(map[string]string)
	for _, key := range []string{
		"app.runtime.role", "app.auth.enabled", "app.auth.username", "app.auth.password", "app.auth.max-failures", "app.auth.lockout",
		"app.rate-limit.enabled", "app.rate-limit.capacity", "app.rate-limit.period", "app.name", "app.version",
		"app.security.production-mode", "app.security.csrf-enabled", "server.servlet.session.cookie.secure", "spring.datasource.password",
		"app.model-max-concurrent-requests", "app.shared-session.enabled", "app.shared-session.namespace", "app.rate-limit.redis.enabled", "app.rate-limit.redis.namespace",
	} {
		value, found, err := p.Get(key)
		if err != nil {
			return FoundationProperties{}, err
		}
		if found {
			values[key] = value
		}
	}
	var result FoundationProperties
	var err error
	role := "combined"
	if value, found := values["app.runtime.role"]; found {
		role = value
	}
	if result.Role, err = ParseRole(role); err != nil {
		return FoundationProperties{}, err
	}
	if result.Auth, err = BindAuthProperties(values); err != nil {
		return FoundationProperties{}, err
	}
	if result.RateLimit, err = BindRateLimitProperties(values); err != nil {
		return FoundationProperties{}, err
	}
	if result.App, err = BindAppProperties(values); err != nil {
		return FoundationProperties{}, err
	}
	result.ModelConcurrency, err = bindPositiveInt(values, "app.model-max-concurrent-requests", 4)
	if err != nil {
		return FoundationProperties{}, err
	}
	for _, entry := range []struct {
		key    string
		target *bool
	}{
		{"app.security.production-mode", &result.Security.Production},
		{"app.security.csrf-enabled", &result.Security.CSRFEnabled},
		{"server.servlet.session.cookie.secure", &result.Security.CookieSecure},
	} {
		*entry.target, err = bindBoolean(values, entry.key, false)
		if err != nil {
			return FoundationProperties{}, err
		}
	}
	result.Security.AuthEnabled = result.Auth.Enabled
	if password := result.Auth.Password(); password != nil {
		result.Security.authPassword = *password
	}
	result.Security.databasePassword = values["spring.datasource.password"]
	if err := result.Security.ValidateProduction(); err != nil {
		return FoundationProperties{}, err
	}
	profiles := p.Profiles()
	if len(profiles) == 0 {
		profiles = p.DefaultProfiles()
	}
	if result.Security.Production || slices.Contains(profiles, "prod") {
		return FoundationProperties{}, errors.New("Go 生产装配尚未验收，禁止使用生产配置启动")
	}
	result.Monitoring = slices.Contains(profiles, "monitoring")
	// 原ConditionalOnProperty(havingValue="true")只忽略大小写，不接受on/1等布尔别名。
	result.SharedSession = strings.EqualFold(values["app.shared-session.enabled"], "true")
	result.RedisRateLimit = strings.EqualFold(values["app.rate-limit.redis.enabled"], "true")
	result.SessionNamespace = values["app.shared-session.namespace"]
	result.RateNamespace = values["app.rate-limit.redis.namespace"]
	return result, nil
}
