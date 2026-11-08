package config

import (
	"maps"
	"slices"
	"time"
)

// LoadProcessProperties 只组合调用方提供的系统快照；文件读取仍需快照中的显式开关。
// 返回的 Environment 和 Properties 均不保留调用方可变 map，dotenv 仍晚于 ConfigData。
func LoadProcessProperties(system map[string]string, directory string, overrides map[string]string) (*Properties, Environment, error) {
	system = maps.Clone(system)
	env, err := LoadEnvironment(func(key string) (string, bool) { value, ok := system[key]; return value, ok }, directory)
	if err != nil {
		return nil, env, err
	}
	properties, err := LoadBaselineProperties(PropertyOptions{System: system, DotEnv: env.dotenv.values, Overrides: overrides})
	if err != nil {
		return nil, env, err
	}
	// The embedded comparison baseline is frozen at an older Java commit.
	// Keep live upload limits current while preserving explicit override precedence.
	properties.sources = slices.Insert(properties.sources, 2, BindingSource{Values: map[string]string{
		"app.knowledge.max-file-size-bytes":         "${AI_KNOWLEDGE_MAX_FILE_SIZE:10485760}",
		"spring.servlet.multipart.max-file-size":    "12MB",
		"spring.servlet.multipart.max-request-size": "14MB",
	}})
	return properties, env, err
}

// ProcessRuntime 仅包含 P1 已支持的监听、退出和基线连接池参数。
// 连接目标/凭据及证书由主命令显式提供，不从 YAML 默认连接地址猜测。
type ProcessRuntime struct {
	Port, ManagementPort, PoolMaximum int
	PoolConnectionTimeout             time.Duration
	ShutdownTimeout                   time.Duration
}

func (p *Properties) ProcessRuntime() (ProcessRuntime, error) {
	values := map[string]string{}
	for _, key := range []string{
		"server.port", "management.server.port", "spring.datasource.hikari.maximum-pool-size",
		"spring.datasource.hikari.connection-timeout", "spring.lifecycle.timeout-per-shutdown-phase",
	} {
		value, found, err := p.Get(key)
		if err != nil {
			return ProcessRuntime{}, err
		}
		if found {
			values[key] = value
		}
	}
	var result ProcessRuntime
	var err error
	for _, item := range []struct {
		key      string
		fallback int
		target   *int
	}{
		{"server.port", 8081, &result.Port},
		{"management.server.port", 9091, &result.ManagementPort},
		{"spring.datasource.hikari.maximum-pool-size", 10, &result.PoolMaximum},
	} {
		*item.target, err = bindPositiveInt(values, item.key, item.fallback)
		if err != nil {
			return ProcessRuntime{}, err
		}
	}
	if result.Port > 65535 || result.ManagementPort > 65535 {
		return ProcessRuntime{}, &propertyError{"server.port/management.server.port", ErrInvalidProperty}
	}
	// Hikari 的 long 属性以毫秒为单位，不使用 Duration 的后缀语法。
	wait := int64(5000)
	if value, found := values["spring.datasource.hikari.connection-timeout"]; found {
		wait, err = bindJavaInteger(value, "spring.datasource.hikari.connection-timeout", 64)
		if err != nil {
			return ProcessRuntime{}, err
		}
	}
	// Hikari 的 0 表示近乎无限等待；P1 不允许无界等待，也不截断超 Go 范围的值。
	if wait < 250 || wait > int64((1<<63-1)/time.Millisecond) {
		return ProcessRuntime{}, &propertyError{"spring.datasource.hikari.connection-timeout", ErrInvalidProperty}
	}
	result.PoolConnectionTimeout = time.Duration(wait) * time.Millisecond
	result.ShutdownTimeout, err = bindPositiveDuration(values, "spring.lifecycle.timeout-per-shutdown-phase", 30*time.Second)
	return result, err
}
