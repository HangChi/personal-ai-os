// Package redisclient 提供显式专用 Redis 连接，不读取环境、默认服务或全局客户端。
package redisclient

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"math"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/redis/go-redis/v9"
	"github.com/redis/go-redis/v9/maintnotifications"
)

const OperationTimeout = 2 * time.Second

type Config struct {
	URI         string `json:"-"`
	RootCertPEM []byte `json:"-"`
}

func (config Config) String() string   { return "RedisConfig{redacted}" }
func (config Config) GoString() string { return config.String() }

type Client struct {
	client    *redis.Client
	closeOnce sync.Once
	closeErr  error
}

func (client *Client) String() string   { return "DedicatedRedisClient{redacted}" }
func (client *Client) GoString() string { return client.String() }

func New(config Config) (*Client, error) {
	options, err := optionsFor(config)
	if err != nil {
		return nil, err
	}
	return &Client{client: redis.NewClient(options)}, nil
}

func optionsFor(config Config) (*redis.Options, error) {
	invalid := func() (*redis.Options, error) { return nil, errors.New("专用 Redis endpoint 配置无效") }
	uri, err := url.Parse(config.URI)
	if err != nil || uri.Opaque != "" || uri.Hostname() == "" || (uri.Scheme != "redis" && uri.Scheme != "rediss") || uri.RawQuery != "" || uri.ForceQuery || strings.Contains(config.URI, "#") {
		return invalid()
	}
	port := 6379
	if uri.Port() != "" {
		port, err = strconv.Atoi(uri.Port())
		if err != nil || port < 1 || port > 65535 {
			return invalid()
		}
	} else if strings.HasSuffix(uri.Host, ":") {
		return invalid()
	}
	db := int64(0)
	if uri.Path != "" && uri.Path != "/" {
		path := strings.TrimPrefix(uri.Path, "/")
		if path == "" {
			return invalid()
		}
		for _, r := range path {
			if r < '0' || r > '9' {
				return invalid()
			}
		}
		db, err = strconv.ParseInt(path, 10, 32)
		if err != nil || db < 0 || db > math.MaxInt32 {
			return invalid()
		}
	}
	username, password := "", ""
	if uri.User != nil {
		// Java URI.getUserInfo 先解码再按第一个冒号拆分，编码过的冒号也参与拆分。
		info, err := url.PathUnescape(uri.User.String())
		if err != nil || !utf8.ValidString(info) {
			return invalid()
		}
		parts := strings.SplitN(info, ":", 2)
		if len(parts) == 2 {
			password = parts[1]
			if !javaBlank(parts[0]) {
				username = parts[0]
			}
		} else {
			password = info
		}
	}
	options := &redis.Options{Addr: net.JoinHostPort(uri.Hostname(), strconv.Itoa(port)), Username: username, Password: password, DB: int(db),
		Protocol: 2, MaxRetries: -1, DialerRetries: 1, DialTimeout: OperationTimeout, ReadTimeout: OperationTimeout,
		WriteTimeout: OperationTimeout, PoolTimeout: OperationTimeout, ContextTimeoutEnabled: true,
		PoolSize: 4, MaxActiveConns: 4, MaxIdleConns: 4, ConnMaxIdleTime: 5 * time.Minute, DisableIdentity: true,
		MaintNotificationsConfig: &maintnotifications.Config{Mode: maintnotifications.ModeDisabled}}
	if uri.Scheme == "rediss" {
		options.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12, ServerName: uri.Hostname()}
		if len(config.RootCertPEM) > 0 {
			roots := x509.NewCertPool()
			if !roots.AppendCertsFromPEM(config.RootCertPEM) {
				return nil, errors.New("Redis 根证书无效")
			}
			options.TLSConfig.RootCAs = roots
		}
	} else if len(config.RootCertPEM) > 0 {
		return nil, errors.New("Redis 明文连接不接受 TLS 根证书")
	}
	return options, nil
}

func javaBlank(value string) bool {
	return strings.TrimFunc(value, func(r rune) bool {
		return (r >= '\t' && r <= '\r') || (r >= '\u001c' && r <= '\u001f') ||
			(r != '\u00a0' && r != '\u2007' && r != '\u202f' && (unicode.Is(unicode.Zs, r) || r == '\u2028' || r == '\u2029'))
	}) == ""
}

// Error 仅保留用于 errors.Is/As 的原因，对外格式化不暴露 URI、密码或命令参数。
type Error struct{ cause error }

func (err *Error) Error() string    { return "专用 Redis 操作失败" }
func (err *Error) Unwrap() error    { return err.cause }
func (err *Error) String() string   { return err.Error() }
func (err *Error) GoString() string { return err.Error() }

func protectedError(ctx context.Context, err error) error {
	if err == nil {
		return nil
	}
	if cause := ctx.Err(); cause != nil {
		err = errors.Join(cause, err)
	}
	return &Error{cause: err}
}

// Eval 不缓存到本地，也不重试；调用方负责静态脚本和显式命名空间。
// 写入后丢响应属于不确定结果，不能在此层透明重放。
func (client *Client) Eval(ctx context.Context, script string, keys []string, args ...any) (any, error) {
	if err := ctx.Err(); err != nil {
		return nil, protectedError(ctx, err)
	}
	op, cancel := context.WithTimeout(ctx, OperationTimeout)
	defer cancel()
	value, err := client.client.Eval(op, script, keys, args...).Result()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	return value, protectedError(op, err)
}

func (client *Client) Ping(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return protectedError(ctx, err)
	}
	op, cancel := context.WithTimeout(ctx, OperationTimeout)
	defer cancel()
	return protectedError(op, client.client.Ping(op).Err())
}

// Close 幂等关闭池；创建者持有生命周期，Store 不擅自关闭调用方的 Client。
func (client *Client) Close() error {
	client.closeOnce.Do(func() { client.closeErr = protectedError(context.Background(), client.client.Close()) })
	return client.closeErr
}
