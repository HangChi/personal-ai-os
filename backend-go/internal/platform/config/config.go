// Package config 承载启动配置；目前只实现安全隔离的迁移进程配置。
package config

import (
	"fmt"
	"net"
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

// Load 不加载 .env，不解析数据库/模型凭据，也不启动任何业务任务。
// 默认端口区别于 Java 8080，避免本轮迁移影响当前可用实例。
func Load(lookup func(string) (string, bool)) (Config, error) {
	port, roleName := 8081, string(Combined)
	if value, found := lookup("SERVER_PORT"); found {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 65535 {
			return Config{}, fmt.Errorf("SERVER_PORT 必须是 1–65535 的整数")
		}
		port = parsed
	}
	if value, found := lookup("APP_RUNTIME_ROLE"); found {
		roleName = value
	}
	role, err := ParseRole(roleName)
	if err != nil {
		return Config{}, err
	}
	return Config{Port: port, Role: role}, nil
}

// Address 暂不允许配置公网地址；正式部署能力在迁移计划 P8 验收。
func (config Config) Address() string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(config.Port))
}
