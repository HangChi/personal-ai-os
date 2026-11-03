package config

import (
	"errors"
	"fmt"
	"strings"
)

// Security 是 Java 生产启动校验的输入，不代表认证/CSRF 已实现。
// 密码不导出，格式化只显示开关，不包含任何配置值。
type Security struct {
	Production       bool
	AuthEnabled      bool
	CSRFEnabled      bool
	CookieSecure     bool
	authPassword     string
	databasePassword string
}

func (security Security) String() string {
	return fmt.Sprintf("Security{production:%t,auth:%t,csrf:%t,secure:%t}", security.Production, security.AuthEnabled, security.CSRFEnabled, security.CookieSecure)
}
func (security Security) GoString() string { return security.String() }

// LoadSecurity 对齐 application.yml、application-prod.yml 的当前安全子集。
// 环境变量优先于 profile 默认值。尚不支持命令行参数、任意 YAML 或 relaxed binding。
func LoadSecurity(lookup Lookup, prodProfile bool) (Security, error) {
	return loadSecurity(lookup, lookup, prodProfile)
}

func loadSecurity(lookup, system Lookup, prodProfile bool) (Security, error) {
	if lookup == nil {
		return Security{}, errors.New("环境变量读取函数不能为空")
	}
	security := Security{}
	for _, setting := range []struct {
		key    string
		target *bool
	}{
		{"APP_SECURITY_PRODUCTION_MODE", &security.Production},
		{"AUTH_ENABLED", &security.AuthEnabled},
		{"APP_SECURITY_CSRF_ENABLED", &security.CSRFEnabled},
		{"SESSION_COOKIE_SECURE", &security.CookieSecure},
	} {
		// prod YAML 为静态 true，.env 处于其后；系统环境仍有更高优先级。
		boolLookup := lookup
		if prodProfile {
			boolLookup = system
			if boolLookup == nil {
				boolLookup = func(string) (string, bool) { return "", false }
			}
		}
		value, err := boolean(boolLookup, setting.key, prodProfile)
		if err != nil {
			return Security{}, err
		}
		*setting.target = value
	}
	security.authPassword, _ = lookup("AUTH_PASSWORD")
	if value, found := lookup("POSTGRES_PASSWORD"); found {
		security.databasePassword = value
	} else {
		security.databasePassword = "personalos"
	}
	return security, nil
}

// ValidateProduction 保留 Java 原校验顺序、中文文案、isBlank 和 UTF-8 字节长度。
func (security Security) ValidateProduction() error {
	if !security.Production {
		return nil
	}
	if !security.AuthEnabled {
		return errors.New("生产配置必须启用 AUTH_ENABLED=true")
	}
	if !security.CSRFEnabled {
		return errors.New("生产配置必须启用 app.security.csrf-enabled=true")
	}
	if !security.CookieSecure {
		return errors.New("生产配置必须启用 SESSION_COOKIE_SECURE=true")
	}
	password := security.authPassword
	if javaStrip(password) == "" || len(password) < 16 || len(password) > 72 ||
		strings.EqualFold(password, "password") || strings.EqualFold(password, "changeme") || strings.EqualFold(password, "personalos") {
		return errors.New("AUTH_PASSWORD 必须为 16-72 字节且不能使用示例密码")
	}
	password = security.databasePassword
	if javaStrip(password) == "" || password == "personalos" || password == "postgres" {
		return errors.New("生产配置必须设置非默认 POSTGRES_PASSWORD")
	}
	return nil
}

// boolean 与 Spring 的常见布尔文字兼容；错误只包含变量名，不回显输入。
func boolean(lookup Lookup, key string, fallback bool) (bool, error) {
	value, found := lookup(key)
	if !found {
		return fallback, nil
	}
	value = strings.TrimFunc(value, func(r rune) bool { return r <= 0x20 })
	switch strings.ToLower(value) {
	case "true", "on", "yes", "1":
		return true, nil
	case "false", "off", "no", "0":
		return false, nil
	default:
		return false, fmt.Errorf("%s 必须是有效布尔值", key)
	}
}

// Profiles 只解析本阶段用到的激活列表；未知 profile 不被误当作 prod。
func Profiles(lookup Lookup) []string {
	value, found := lookup("SPRING_PROFILES_ACTIVE")
	if !found || strings.TrimSpace(value) == "" {
		value, _ = lookup("SPRING_PROFILES_DEFAULT")
	}
	profiles := make([]string, 0)
	for _, profile := range strings.Split(value, ",") {
		profile = strings.TrimSpace(profile)
		if profile != "" {
			profiles = append(profiles, profile)
		}
	}
	return profiles
}
