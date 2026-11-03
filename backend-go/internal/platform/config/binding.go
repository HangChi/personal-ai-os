package config

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var ErrInvalidProperty = errors.New("配置属性绑定失败")

// propertyError只保留程序内固定属性名与安全原因，不包装带原始值的转换错误。
type propertyError struct {
	key   string
	cause error
}

func (err *propertyError) Error() string { return err.key + "：配置无效" }
func (err *propertyError) Unwrap() error { return err.cause }

// AuthProperties是原AuthProperties record的绑定结果，不代表AuthService已启动。
// 用户名/密码不参与JSON或常规格式化；明文只通过Password显式交给凭据编码器。
type AuthProperties struct {
	Enabled     bool
	Username    string `json:"-"`
	MaxFailures int
	Lockout     time.Duration
	password    *string
}

func (properties AuthProperties) String() string {
	return fmt.Sprintf("AuthProperties{enabled:%t,maxFailures:%d,lockout:%s}", properties.Enabled, properties.MaxFailures, properties.Lockout)
}
func (properties AuthProperties) GoString() string { return properties.String() }

// Password返回独立副本，保留Java null与显式空字符串的区别。调用方负责明文生命周期。
func (properties AuthProperties) Password() *string {
	if properties.password == nil {
		return nil
	}
	value := *properties.password
	return &value
}

type RateLimitProperties struct {
	Enabled  bool
	Capacity int
	Period   time.Duration
}

type AgentExecutionProperties struct {
	MaxModelCalls          int
	MaxConsecutiveFailures int
}

// BindAuthProperties只消费调用方提供的已解析canonical字符串属性。
// 不读取环境、YAML、.env或展开占位符；默认值取record的DefaultValue/构造器，
// 不偷偷叠加application.yml。未知字段与原默认Binder一样忽略。
func BindAuthProperties(properties map[string]string) (AuthProperties, error) {
	var result AuthProperties
	var err error
	result.Enabled, err = bindBoolean(properties, "app.auth.enabled", false)
	if err != nil {
		return AuthProperties{}, err
	}
	result.MaxFailures, err = bindPositiveInt(properties, "app.auth.max-failures", 5)
	if err != nil {
		return AuthProperties{}, err
	}
	result.Lockout, err = bindPositiveDuration(properties, "app.auth.lockout", time.Minute)
	if err != nil {
		return AuthProperties{}, err
	}
	username := properties["app.auth.username"]
	if !utf8.ValidString(username) {
		return AuthProperties{}, &propertyError{"app.auth.username", ErrInvalidProperty}
	}
	result.Username = javaStrip(username)
	if result.Username == "" {
		result.Username = "admin"
	}
	if password, found := properties["app.auth.password"]; found {
		if !utf8.ValidString(password) {
			return AuthProperties{}, &propertyError{"app.auth.password", ErrInvalidProperty}
		}
		result.password = &password
	}
	return result, nil
}

func BindRateLimitProperties(properties map[string]string) (RateLimitProperties, error) {
	var result RateLimitProperties
	var err error
	result.Enabled, err = bindBoolean(properties, "app.rate-limit.enabled", true)
	if err != nil {
		return RateLimitProperties{}, err
	}
	result.Capacity, err = bindPositiveInt(properties, "app.rate-limit.capacity", 60)
	if err != nil {
		return RateLimitProperties{}, err
	}
	result.Period, err = bindPositiveDuration(properties, "app.rate-limit.period", time.Minute)
	if err != nil {
		return RateLimitProperties{}, err
	}
	return result, nil
}

func BindAgentExecutionProperties(properties map[string]string) (AgentExecutionProperties, error) {
	var result AgentExecutionProperties
	var err error
	result.MaxModelCalls, err = bindPositiveInt(properties, "app.agent.max-model-calls", 12)
	if err != nil {
		return AgentExecutionProperties{}, err
	}
	result.MaxConsecutiveFailures, err = bindPositiveInt(properties, "app.agent.max-consecutive-failures", 2)
	if err != nil {
		return AgentExecutionProperties{}, err
	}
	return result, nil
}

func bindBoolean(properties map[string]string, key string, fallback bool) (bool, error) {
	value, err := boolean(func(key string) (string, bool) { value, found := properties[key]; return value, found }, key, fallback)
	if err != nil {
		return false, &propertyError{key, ErrInvalidProperty}
	}
	return value, nil
}

func bindPositiveDuration(properties map[string]string, key string, fallback time.Duration) (time.Duration, error) {
	source, found := properties[key]
	if !found {
		return fallback, nil
	}
	value, err := ParseDuration(source)
	if err != nil {
		cause := ErrInvalidProperty
		if errors.Is(err, ErrScalarOverflow) {
			cause = ErrScalarOverflow
		}
		return 0, &propertyError{key, cause}
	}
	// Duration转换空串返回null；Binder随后使用DefaultValue。纯空白不属于null。
	if value == nil {
		return fallback, nil
	}
	if *value <= 0 {
		return 0, &propertyError{key, ErrInvalidProperty}
	}
	return *value, nil
}

func bindPositiveInt(properties map[string]string, key string, fallback int) (int, error) {
	source, found := properties[key]
	if !found {
		return fallback, nil
	}
	// Spring NumberUtils移除所有Java空白，包含数字中间的空白。
	source = strings.Map(func(r rune) rune {
		if javaWhitespace(r) {
			return -1
		}
		return r
	}, source)
	negative := strings.HasPrefix(source, "-")
	if negative {
		source = source[1:]
	}
	base := 10
	if strings.HasPrefix(source, "0x") || strings.HasPrefix(source, "0X") {
		base, source = 16, source[2:]
	} else if strings.HasPrefix(source, "#") {
		base, source = 16, source[1:]
	} else if !negative && strings.HasPrefix(source, "+") {
		source = source[1:]
	}
	var digits strings.Builder
	for _, r := range source {
		digit := javaIntDigit(r, base)
		if digit < 0 {
			return 0, &propertyError{key, ErrInvalidProperty}
		}
		digits.WriteByte("0123456789abcdef"[digit])
	}
	text := digits.String()
	if negative {
		text = "-" + text
	}
	value, err := strconv.ParseInt(text, base, 32)
	if err != nil || value <= 0 {
		return 0, &propertyError{key, ErrInvalidProperty}
	}
	return int(value), nil
}

// Integer.parseInt逐UTF-16 char使用Character.digit，支持BMP十进制数字与
// 全角A-F，但不接受补充平面的数学数字或Unicode正负号。
func javaIntDigit(r rune, base int) int {
	if r <= 0xffff {
		for _, span := range unicode.Digit.R16 {
			if r >= rune(span.Lo) && r <= rune(span.Hi) && (r-rune(span.Lo))%rune(span.Stride) == 0 {
				return int((r-rune(span.Lo))/rune(span.Stride)) % 10
			}
		}
	}
	if base == 16 {
		switch {
		case r >= 'a' && r <= 'f':
			return int(r-'a') + 10
		case r >= 'A' && r <= 'F':
			return int(r-'A') + 10
		case r >= 'ａ' && r <= 'ｆ':
			return int(r-'ａ') + 10
		case r >= 'Ａ' && r <= 'Ｆ':
			return int(r-'Ａ') + 10
		}
	}
	return -1
}
