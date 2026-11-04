package main

import (
	"context"
	"errors"
	"flag"
	"io"
	"log/slog"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/app"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/observability"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

type commandOptions struct {
	foundation, allowBaselineV1, p5, p6, p7, p8, container bool
	migrations                                             string
}

func parseCommand(args []string) (commandOptions, error) {
	var options commandOptions
	flags := flag.NewFlagSet("personalos", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	flags.BoolVar(&options.foundation, "foundation", false, "显式启用 P1 基础运行时")
	flags.BoolVar(&options.p5, "p5", false, "启用 P5 记忆、日程及提醒")
	flags.BoolVar(&options.p6, "p6", false, "启用 P6 知识、RAG 与后台角色（包含P5）")
	flags.BoolVar(&options.p7, "p7", false, "启用 P7 MCP、搜索与数据交换（包含P6）")
	flags.BoolVar(&options.p8, "p8", false, "启用 P8 运维候选运行时（包含P7，尚未验收发布）")
	flags.BoolVar(&options.container, "container", false, "显式P8容器网络监听，要求认证与CSRF，不解除发布门禁")
	flags.StringVar(&options.migrations, "migrations", "", "原 Java 迁移目录的绝对路径，只读校验")
	flags.BoolVar(&options.allowBaselineV1, "allow-baseline-v1", false, "允许真实 Flyway 默认 V1 基线标记")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return options, err
		}
		return commandOptions{}, errors.New("启动参数无效，请使用 --help 查看支持参数")
	}
	if flags.NArg() != 0 || (!options.foundation && (options.migrations != "" || options.allowBaselineV1 || options.p5 || options.p6 || options.p7 || options.p8 || options.container)) {
		return commandOptions{}, errors.New("迁移参数和 --p5/--p6/--p7/--p8 必须与 --foundation 一起显式设置，且不接受位置参数")
	}
	if options.container && !options.p8 {
		return commandOptions{}, errors.New("--container 必须与显式 --foundation --p8 一起使用")
	}
	if options.foundation && (!filepath.IsAbs(options.migrations) || options.migrations == string(filepath.Separator)) {
		return commandOptions{}, errors.New("基础模式必须显式提供原 Java 迁移目录的绝对路径")
	}
	if options.p8 {
		options.p7 = true
	}
	if options.p7 {
		options.p6 = true
	}
	if options.p6 {
		options.p5 = true
	}
	return options, nil
}

func runCommand(ctx context.Context, args []string, logger *slog.Logger) error {
	options, err := parseCommand(args)
	if errors.Is(err, flag.ErrHelp) {
		_, err = io.WriteString(os.Stdout, "用法: personalos [--foundation --migrations /absolute/java/db/migration [--allow-baseline-v1] [--p5 | --p6 | --p7 | --p8]]\n默认仅离线诊断；基础模式要求显式 SERVER_PORT 和 POSTGRES_HOST/PORT/DB/USER/PASSWORD。\n")
		return err
	}
	if err != nil {
		return err
	}
	if !options.foundation {
		return run(ctx, logger)
	}
	system := map[string]string{}
	for _, entry := range os.Environ() {
		key, value, _ := strings.Cut(entry, "=")
		system[key] = value
	}
	return runFoundation(ctx, options, system, logger)
}

func explicitPostgres(env config.Environment, properties *config.Properties) (database.PostgresOptions, error) {
	options := database.DefaultPostgresOptions()
	for _, key := range []string{"SERVER_PORT", "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"} {
		value, found := env.Lookup(key)
		if !found || value == "" {
			return options, errors.New("基础模式必须显式设置 SERVER_PORT 和 POSTGRES_HOST/PORT/DB/USER/PASSWORD，禁止使用 YAML 连接默认值")
		}
	}
	// 使用真实来源解析后的 canonical 值，显式变量只负责阻止默认连接目标。
	jdbc, _, err := properties.Get("spring.datasource.url")
	if err != nil {
		return options, err
	}
	if !strings.HasPrefix(jdbc, "jdbc:postgresql://") {
		return options, errors.New("P1 仅支持显式单主机 PostgreSQL JDBC TCP 地址")
	}
	endpoint, err := url.Parse(strings.TrimPrefix(jdbc, "jdbc:"))
	if err != nil || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.ForceQuery || endpoint.Fragment != "" || strings.Contains(jdbc, "#") || endpoint.Opaque != "" || endpoint.Port() == "" || endpoint.Hostname() == "" {
		return options, errors.New("P1 PostgreSQL 地址无效；不支持 JDBC 查询、隐式端口或多主机")
	}
	options.Host, options.Database = endpoint.Hostname(), strings.TrimPrefix(endpoint.Path, "/")
	options.Port, err = strconv.Atoi(endpoint.Port())
	if err != nil || strings.ContainsAny(options.Database, "/\\") || options.Database == "" {
		return options, errors.New("P1 PostgreSQL 端口或库名无效")
	}
	options.Username, _, err = properties.Get("spring.datasource.username")
	if err != nil {
		return options, err
	}
	options.Password, _, err = properties.Get("spring.datasource.password")
	if err != nil {
		return options, err
	}
	if value, found := env.Lookup("GO_POSTGRES_SSL_MODE"); found {
		options.SSLMode = value
	}
	if value, found := env.Lookup("GO_POSTGRES_ROOT_CERT"); found {
		options.RootCert = value
	}
	return options, nil
}

func dedicatedRedis(properties *config.Properties, env config.Environment, prefix, certKey string) (*redisclient.Client, error) {
	uri, _, err := properties.Get(prefix + ".uri")
	if err != nil {
		return nil, err
	}
	var root []byte
	if path, found := env.Lookup(certKey); found {
		if path == "" || !filepath.IsAbs(path) {
			return nil, errors.New("专用 Redis 根证书必须为显式绝对路径")
		}
		root, err = os.ReadFile(path)
		if err != nil {
			return nil, errors.New("读取专用 Redis 根证书失败")
		}
	}
	return redisclient.New(redisclient.Config{URI: uri, RootCertPEM: root})
}

// runFoundation 拥有全部资源：基础应用退出后才关闭三个专用 Redis 客户端。
// 所有配置与迁移文件均在监听/连接之前验证；不自动 migrate/repair 或启动业务 Worker。
func runFoundation(ctx context.Context, options commandOptions, system map[string]string, logger *slog.Logger) (err error) {
	if ctx == nil || logger == nil {
		return errors.New("基础进程需要 context 与日志")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	properties, env, err := config.LoadProcessProperties(system, "", nil)
	if err != nil {
		return err
	}
	settings, err := properties.Foundation()
	if err != nil {
		return err
	}
	if options.container && (!options.p8 || !settings.Auth.Enabled || !settings.Security.CSRFEnabled) {
		return errors.New("P8容器网络必须启用全部模块、认证与CSRF")
	}
	logging, err := properties.Logging()
	if err != nil {
		return err
	}
	logger = observability.NewLogger(os.Stderr, logging)
	if err := properties.ValidateFoundationExtensions(); err != nil {
		return err
	}
	runtime, err := properties.ProcessRuntime()
	if err != nil {
		return err
	}
	postgres, err := explicitPostgres(env, properties)
	if err != nil {
		return err
	}
	if settings.Monitoring && runtime.Port == runtime.ManagementPort {
		return errors.New("基础业务与管理端口必须不同")
	}
	connector, err := database.NewPostgresConnector(postgres)
	if err != nil {
		return err
	}
	var session, rate, embedding, candidate *redisclient.Client
	var foundation *app.Foundation
	defer func() {
		if foundation != nil {
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			closeErr := foundation.Close(cleanup)
			cancel()
			err = errors.Join(err, closeErr)
			if closeErr != nil {
				return
			} // 在途操作未退出时不能提前关闭借用客户端。
		}
		if rate != nil {
			err = errors.Join(err, rate.Close())
		}
		if embedding != nil {
			err = errors.Join(err, embedding.Close())
		}
		if candidate != nil {
			err = errors.Join(err, candidate.Close())
		}
		if session != nil {
			err = errors.Join(err, session.Close())
		}
	}()
	if settings.SharedSession {
		session, err = dedicatedRedis(properties, env, "app.shared-session", "GO_REDIS_SESSION_ROOT_CERT")
		if err != nil {
			return err
		}
	}
	if settings.RedisRateLimit {
		rate, err = dedicatedRedis(properties, env, "app.rate-limit.redis", "GO_REDIS_RATE_LIMIT_ROOT_CERT")
		if err != nil {
			return err
		}
	}
	redisEnabled, _, redisErr := properties.Get("app.redis.enabled")
	if redisErr != nil {
		return redisErr
	}
	if strings.EqualFold(redisEnabled, "true") {
		embedding, err = dedicatedRedis(properties, env, "app.redis", "GO_REDIS_EMBEDDING_ROOT_CERT")
		if err != nil {
			return err
		}
		if options.p6 {
			enabled, _, e := properties.Get("app.knowledge.candidate-cache.enabled")
			if e != nil {
				return e
			}
			if strings.EqualFold(enabled, "true") {
				candidate, err = dedicatedRedis(properties, env, "app.redis", "GO_REDIS_CANDIDATE_ROOT_CERT")
				if err != nil {
					return err
				}
			}
		}
	}
	pool := database.DefaultPoolOptions(settings.Role.Worker())
	pool.MaxOpen, pool.MaxIdle, pool.ConnectionTimeout = runtime.PoolMaximum, runtime.PoolMaximum, runtime.PoolConnectionTimeout
	foundation, err = app.OpenFoundation(ctx, properties, app.FoundationOptions{
		Connector: connector, Migrations: os.DirFS(options.migrations), Schema: "public", AllowBaselineV1: options.allowBaselineV1,
		Pool: &pool, SessionRedis: session, RateRedis: rate, EmbeddingRedis: embedding, CandidateRedis: candidate, EnableP2: true, EnableP3: true, EnableP4: true, EnableP5: options.p5, EnableP6: options.p6, EnableP7: options.p7, EnableP8: options.p8, ContainerNetwork: options.container,
	}, logger)
	if err != nil {
		return err
	}
	bindHost := "127.0.0.1"
	if options.container {
		bindHost = "0.0.0.0"
	}
	business, err := net.Listen("tcp", net.JoinHostPort(bindHost, strconv.Itoa(runtime.Port)))
	if err != nil {
		return errors.New("基础业务端口监听失败")
	}
	defer business.Close()
	var management net.Listener
	if settings.Monitoring {
		management, err = net.Listen("tcp", net.JoinHostPort(bindHost, strconv.Itoa(runtime.ManagementPort)))
		if err != nil {
			return errors.New("基础管理端口监听失败")
		}
		defer management.Close()
	}
	logger.Warn("Go 基础运行时已启动，业务迁移尚未完成，禁止替换 Java 实例", "role", settings.Role,
		"address", business.Addr().String(), "monitoring", settings.Monitoring, "dotenv", env.Status(), "poolMax", pool.MaxOpen)
	return foundation.Serve(ctx, business, management, runtime.ShutdownTimeout)
}
