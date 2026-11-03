package config_test

import (
	"errors"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
)

func TestBoundAuthValuesDriveRealCredentialLockout(t *testing.T) {
	settings, err := config.BindAuthProperties(map[string]string{
		"app.auth.enabled": "true", "app.auth.username": "  合成用户  ",
		"app.auth.password": "fixture-credential-32", "app.auth.max-failures": "2", "app.auth.lockout": "30s",
	})
	if err != nil {
		t.Fatal(err)
	}
	service, err := auth.New(auth.Options{Enabled: settings.Enabled, Username: settings.Username,
		Password: *settings.Password(), MaxFailures: settings.MaxFailures, Lockout: settings.Lockout})
	if err != nil {
		t.Fatal(err)
	}
	username, password := "合成用户", "fixture-credential-32"
	if ok, err := service.Verify(&username, &password); err != nil || !ok {
		t.Fatal("绑定后的真实凭据无法登录", err)
	}
	wrong := "fixture-wrong-password"
	for range 2 {
		if ok, err := service.Verify(&username, &wrong); err != nil || ok {
			t.Fatal("到阈值当次应失败但未提前锁定", err)
		}
	}
	_, err = service.Verify(&username, &password)
	var locked *auth.LockedError
	if !errors.As(err, &locked) || locked.RetryAfter < 29 || locked.RetryAfter > 30 {
		t.Fatal("绑定的锁定预算未生效", err)
	}
	if strings.Contains(service.String(), "fixture-credential") {
		t.Fatal("服务泄漏凭据")
	}
}

func TestRecordBindingDoesNotBypassCredentialOrStartupValidation(t *testing.T) {
	// 原record允许尚未提供密码；AuthService才负责启用认证后的必填校验。
	settings, err := config.BindAuthProperties(map[string]string{"app.auth.enabled": "true"})
	if err != nil || settings.Password() != nil {
		t.Fatal("record的null语义改变", err)
	}
	if _, err := auth.New(auth.Options{Enabled: settings.Enabled, Username: settings.Username,
		MaxFailures: settings.MaxFailures, Lockout: settings.Lockout}); err == nil {
		t.Fatal("缺失密码绕过服务守卫")
	}
	if _, err := config.Load(func(key string) (string, bool) { return "true", key == "AUTH_ENABLED" }); err == nil {
		t.Fatal("独立绑定能力不能解除诊断应用的认证启动守卫")
	}
}

func TestBoundRateLimitPreservesCapacityAndDisabledValidation(t *testing.T) {
	settings, err := config.BindRateLimitProperties(map[string]string{"app.rate-limit.capacity": "2", "app.rate-limit.period": "1d"})
	if err != nil {
		t.Fatal(err)
	}
	bucket, err := ratelimit.NewLocal(ratelimit.Options{Enabled: settings.Enabled, Capacity: settings.Capacity, Period: settings.Period})
	if err != nil {
		t.Fatal(err)
	}
	if !bucket.TryAcquire() || !bucket.TryAcquire() || bucket.TryAcquire() {
		t.Fatal("绑定的桶容量未生效")
	}
	settings, err = config.BindRateLimitProperties(map[string]string{"app.rate-limit.enabled": "false"})
	if err != nil {
		t.Fatal(err)
	}
	bucket, err = ratelimit.NewLocal(ratelimit.Options{Enabled: settings.Enabled, Capacity: settings.Capacity, Period: settings.Period})
	if err != nil {
		t.Fatal(err)
	}
	for range 100 {
		if !bucket.TryAcquire() {
			t.Fatal("显式关闭后仍被限流")
		}
	}
	for _, key := range []string{"app.rate-limit.capacity", "app.rate-limit.period"} {
		if _, err := config.BindRateLimitProperties(map[string]string{"app.rate-limit.enabled": "false", key: "0"}); err == nil {
			t.Fatal("关闭限流不能跳过非法配置校验")
		}
	}
}
