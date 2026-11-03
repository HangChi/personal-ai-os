package config

import (
	"net/http"
	"strings"
	"time"
)

// SessionProperties区分Servlet本地会话与原@EnableRedisHttpSession的固定30分钟超时。
type SessionProperties struct {
	Timeout time.Duration
	Cookie  http.Cookie
}

func (p *Properties) Session(shared bool) (SessionProperties, error) {
	result := SessionProperties{Timeout: 30 * time.Minute, Cookie: http.Cookie{Name: "JSESSIONID", Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode}}
	if shared {
		result.Cookie.Name = "SESSION"
	}
	keys := []string{"timeout", "cookie.name", "cookie.path", "cookie.domain", "cookie.http-only", "cookie.same-site", "cookie.max-age"}
	for _, suffix := range keys {
		key := "server.servlet.session." + suffix
		value, found, err := p.Get(key)
		if err != nil {
			return result, err
		}
		if !found {
			continue
		}
		// 原共享CookieSerializer/超时不消费这些Servlet扩展；明确拒绝避免配置看似生效。
		if shared {
			return result, &propertyError{key, ErrInvalidProperty}
		}
		switch suffix {
		case "timeout":
			if !strings.ContainsAny(value, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") {
				value += "s"
			}
			d, err := ParseDuration(value)
			if err != nil || d == nil || *d <= 0 {
				return result, &propertyError{key, ErrInvalidProperty}
			}
			result.Timeout = *d
		case "cookie.name":
			result.Cookie.Name = value
		case "cookie.path":
			if !strings.HasPrefix(value, "/") {
				return result, &propertyError{key, ErrInvalidProperty}
			}
			result.Cookie.Path = value
		case "cookie.domain":
			result.Cookie.Domain = value
		case "cookie.http-only":
			v, err := bindBoolean(map[string]string{key: value}, key, true)
			if err != nil {
				return result, err
			}
			result.Cookie.HttpOnly = v
		case "cookie.same-site":
			switch strings.ToLower(value) {
			case "lax":
				result.Cookie.SameSite = http.SameSiteLaxMode
			case "strict":
				result.Cookie.SameSite = http.SameSiteStrictMode
			case "none":
				result.Cookie.SameSite = http.SameSiteNoneMode
			case "":
				result.Cookie.SameSite = http.SameSiteDefaultMode
			default:
				return result, &propertyError{key, ErrInvalidProperty}
			}
		case "cookie.max-age":
			// 认证Cookie继续为会话Cookie；持久Cookie需要另验浏览器退出语义。
			return result, &propertyError{key, ErrInvalidProperty}
		}
	}
	if err := result.Cookie.Valid(); err != nil {
		return result, &propertyError{"server.servlet.session.cookie", ErrInvalidProperty}
	}
	return result, nil
}
