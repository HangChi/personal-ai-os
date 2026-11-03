// Package config 承载启动配置；目前只实现安全隔离的迁移进程配置。
package config

import (
	"errors"
	"fmt"
	"net"
	"slices"
	"strconv"
)

type Role string

const (
	Combined Role = "combined"
	API      Role = "api"
	Index    Role = "index"
	Review   Role = "review"
	Delivery Role = "delivery"
)

func ParseRole(value string) (Role, error) {
	role := Role(value)
	switch role {
	case Combined, API, Index, Review, Delivery:
		return role, nil
	default:
		return "", fmt.Errorf("APP_RUNTIME_ROLE 必须是 combined/api/index/review/delivery")
	}
}

func (role Role) Allows(task string) bool { return role == Combined || string(role) == task }
func (role Role) Worker() bool            { return !role.Allows("api") }

type Config struct {
	Port int
	Role Role
}

// Load 不加载文件或连接数据库；配置来源由调用方显式组合。
// 默认端口区别于 Java 8080，避免本轮迁移影响当前可用实例。
func Load(lookup func(string) (string, bool)) (Config, error) {
	return loadConfig(lookup, lookup)
}

// Config 保留 .env 低于 profile 静态配置的优先级；Load 的输入则视为系统环境。
func (env Environment) Config() (Config, error) {
	return loadConfig(env.Lookup, env.system)
}

func loadConfig(lookup, system Lookup) (Config, error) {
	if lookup == nil {
		return Config{}, errors.New("环境变量读取函数不能为空")
	}
	port, roleName := 8081, string(Combined)
	profiles := Profiles(lookup)
	roleFromProfile := false
	for _, profile := range profiles {
		switch profile {
		case "runtime-api":
			roleName = string(API)
			roleFromProfile = true
		case "worker-index":
			roleName = string(Index)
			roleFromProfile = true
		case "worker-review":
			roleName = string(Review)
			roleFromProfile = true
		case "worker-delivery":
			roleName = string(Delivery)
			roleFromProfile = true
		}
	}
	if value, found := lookup("SERVER_PORT"); found {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 65535 {
			return Config{}, fmt.Errorf("SERVER_PORT 必须是 1–65535 的整数")
		}
		port = parsed
	}
	roleLookup := lookup
	if roleFromProfile {
		roleLookup = system
	}
	if roleLookup != nil {
		if value, found := roleLookup("APP_RUNTIME_ROLE"); found {
			roleName = value
		}
	}
	role, err := ParseRole(roleName)
	if err != nil {
		return Config{}, err
	}
	security, err := loadSecurity(lookup, system, slices.Contains(profiles, "prod"))
	if err != nil {
		return Config{}, err
	}
	if err := security.ValidateProduction(); err != nil {
		return Config{}, err
	}
	if security.Production || slices.Contains(profiles, "prod") {
		return Config{}, errors.New("Go 生产装配尚未迁移，禁止使用生产配置启动")
	}
	if security.AuthEnabled || security.CSRFEnabled {
		return Config{}, errors.New("Go 认证与 CSRF 尚未迁移，不能启用对应安全开关启动")
	}
	return Config{Port: port, Role: role}, nil
}

// Address 暂不允许配置公网地址；正式部署能力在迁移计划 P8 验收。
func (config Config) Address() string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(config.Port))
}
