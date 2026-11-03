// Package auth 移植单用户认证契约；共享会话与 CSRF 就绪前不接入诊断应用。
package auth

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

// Options 仅作为构造输入；调用方负责明文的生命周期。
// Service 只保留 BCrypt 哈希及非敏感配置。
type Options struct {
	Enabled     bool
	Username    string
	Password    string `json:"-"`
	MaxFailures int
	Lockout     time.Duration
}

func DefaultOptions() Options {
	return Options{Username: "admin", MaxFailures: 5, Lockout: 60 * time.Second}
}

func (options Options) String() string {
	return fmt.Sprintf("AuthOptions{enabled:%t,maxFailures:%d,lockout:%s}",
		options.Enabled, options.MaxFailures, options.Lockout)
}
func (options Options) GoString() string { return options.String() }

type passwordCodec interface {
	encode(string) ([]byte, error)
	match(string, []byte) bool
}

type bcryptCodec struct{}

func (bcryptCodec) encode(raw string) ([]byte, error) {
	return bcrypt.GenerateFromPassword([]byte(raw), 10)
}
func (bcryptCodec) match(raw string, hash []byte) bool {
	// Spring 的 AbstractValidatingPasswordEncoder 拒绝空密码。
	// 校验保留原 Java 的 >72 字节行为；只有生成配置密码的哈希时拒绝超长。
	return raw != "" && bcrypt.CompareHashAndPassword(hash, []byte(raw)) == nil
}

type Service struct {
	enabled      bool
	username     string
	passwordHash []byte
	maxFailures  int
	lockout      time.Duration
	codec        passwordCodec
	now          func() time.Time
	mu           sync.Mutex
	failures     int
	lockedUntil  time.Time
}

func New(options Options) (*Service, error) {
	return newService(options, bcryptCodec{}, time.Now)
}

func newService(options Options, codec passwordCodec, now func() time.Time) (*Service, error) {
	if options.MaxFailures <= 0 || options.MaxFailures > math.MaxInt32 {
		return nil, errors.New("app.auth.max-failures 必须为正的 Java int")
	}
	if options.Lockout <= 0 {
		return nil, errors.New("app.auth.lockout 必须为正的时长")
	}
	if !utf8.ValidString(options.Username) || !utf8.ValidString(options.Password) {
		return nil, errors.New("认证配置必须为有效 UTF-8")
	}
	username := javaStrip(options.Username)
	if username == "" {
		username = "admin"
	}
	if options.Enabled && javaStrip(options.Password) == "" {
		return nil, errors.New("已开启访问控制（AUTH_ENABLED=true），但未设置 AUTH_PASSWORD。请配置密码后重启，或将 AUTH_ENABLED 设为 false。")
	}
	service := &Service{enabled: options.Enabled, username: username,
		maxFailures: options.MaxFailures, lockout: options.Lockout, codec: codec, now: now}
	if options.Enabled {
		hash, err := codec.encode(options.Password)
		if err != nil {
			// 不向上传播可能包含凭据的编码器错误。
			return nil, errors.New("AUTH_PASSWORD 的 BCrypt 编码失败（密码最多 72 个 UTF-8 字节）")
		}
		service.passwordHash = hash
	}
	return service, nil
}

// LockedError 对应 RATE_LIMITED (42900)。达到阈值的那次尝试仍返回 false, nil；
// 下一次非 null 尝试才返回此错误。
type LockedError struct{ RetryAfter int64 }

func (err *LockedError) Error() string {
	return fmt.Sprintf("登录尝试过于频繁，请 %d 秒后再试", err.RetryAfter)
}

// Verify 区分 Java 的 null/空字符串，用同一把锁串行校验密码和更新锁定状态，
// 避免并发失败绕过阈值。用户名错误仍校验密码；关闭或 null 输入在检查锁定前返回。
func (service *Service) Verify(username, rawPassword *string) (bool, error) {
	if !service.enabled || username == nil || rawPassword == nil {
		return false, nil
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	now := service.now()
	if service.lockedUntil.After(now) {
		seconds := max(int64(1), int64(service.lockedUntil.Sub(now)/time.Second))
		return false, &LockedError{RetryAfter: seconds}
	}
	passwordOK := service.codec.match(*rawPassword, service.passwordHash)
	usernameOK := service.username == javaStrip(*username)
	if passwordOK && usernameOK {
		service.failures = 0
		service.lockedUntil = time.Time{}
		return true, nil
	}
	service.failures++
	if service.failures >= service.maxFailures {
		service.failures = 0
		service.lockedUntil = service.now().Add(service.lockout)
	}
	return false, nil
}

func (service *Service) Enabled() bool    { return service.enabled }
func (service *Service) Username() string { return service.username }
func (service *Service) String() string {
	return fmt.Sprintf("AuthService{enabled:%t}", service.enabled)
}
func (service *Service) GoString() string { return service.String() }

// Java String.strip/Character.isWhitespace 与 Go TrimSpace 对 NBSP、NEL、
// U+001C..U+001F 的规则不同，此处与配置解析器保持一致。
func javaStrip(value string) string {
	return strings.TrimFunc(value, func(r rune) bool {
		return (r >= '\t' && r <= '\r') || (r >= '\u001c' && r <= '\u001f') ||
			(r != '\u00a0' && r != '\u2007' && r != '\u202f' &&
				(unicode.Is(unicode.Zs, r) || r == '\u2028' || r == '\u2029'))
	})
}
