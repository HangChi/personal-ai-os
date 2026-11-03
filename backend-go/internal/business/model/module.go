package model

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

// Module 显式装配P3，不连接上游模型；存储与Redis由Foundation持有生命周期。
type Module struct {
	Service       *Service
	Runtime       *Runtime
	Embedding     *CachedEmbedding
	Shared        *RedisEmbeddingCache
	Handler       http.Handler
	EmbeddingName string
}

func OpenModule(ctx context.Context, pool *database.Pool, p *config.Properties, transport http.RoundTripper, redis *redisclient.Client, logger *slog.Logger) (*Module, error) {
	get := func(k string) (string, error) { v, _, e := p.Get(k); return v, e }
	key, err := get("app.model-key-encryption-key")
	if err != nil {
		return nil, err
	}
	keys, err := NewKeyCodec(key)
	if err != nil {
		return nil, err
	}
	resolver, err := NewResolver(p)
	if err != nil {
		return nil, err
	}
	store, err := NewPostgresReadStore(pool, keys)
	if err != nil {
		return nil, err
	}
	if err = CheckPrivileges(ctx, pool); err != nil {
		return nil, err
	}
	runtime := NewRuntime(transport)
	service, err := NewService(store, resolver, runtime)
	if err != nil {
		return nil, err
	}
	module := &Module{Service: service, Runtime: runtime}
	runtime.Register(service.ResolveNamed)
	provider, err := get("spring.ai.model.chat")
	if err != nil {
		return nil, err
	}
	if provider != "none" {
		v := resolver.Resolve(Values{})
		protocol := OpenAI
		switch provider {
		case "openai":
		case "ollama":
			protocol = Ollama
			v.BaseURL, err = get("spring.ai.ollama.base-url")
			if err != nil {
				return nil, err
			}
			v.ChatModel, err = get("spring.ai.ollama.chat.model")
			if err != nil {
				return nil, err
			}
			temp, e := get("spring.ai.ollama.chat.temperature")
			if e != nil {
				return nil, e
			}
			v.Temperature = parseTemperature(temp)
		default:
			return nil, errors.New("尚未支持该聊天模型provider")
		}
		client, e := NewChatClient(v, protocol, transport)
		if e != nil {
			return nil, e
		}
		if protocol == OpenAI {
			extra, e := p.Prefix("spring.ai.openai.chat.options.extra-body.")
			if e != nil {
				return nil, e
			}
			client.extra = map[string]any{}
			for k, v := range extra {
				client.extra[k] = v
			}
		}
		runtime.Replace(client)
	}
	// 启动期坏配置可修复，记录固定文案，不输出原异常/凭据。
	if err = service.Startup(ctx); err != nil {
		logger.Warn("启动期模型配置应用失败，沿用启动模型")
	}
	embedding, err := get("spring.ai.model.embedding")
	if err != nil {
		return nil, err
	}
	sharedEnabled, err := get("app.redis.enabled")
	if err != nil {
		return nil, err
	}
	enabledRedis, e := strconv.ParseBool(sharedEnabled)
	if e != nil {
		return nil, errors.New("Redis开关格式不合法")
	}
	if enabledRedis != (redis != nil) {
		return nil, errors.New("嵌入Redis开关必须与显式专用客户端一致")
	}
	if redis != nil {
		ns, e := get("app.redis.namespace")
		if e != nil {
			return nil, e
		}
		timeoutRaw, e := get("app.redis.timeout-ms")
		if e != nil {
			return nil, e
		}
		ms, e := strconv.Atoi(timeoutRaw)
		if e != nil {
			return nil, errors.New("Redis超时格式不合法")
		}
		module.Shared, e = NewRedisEmbeddingCache(redis, ns, time.Duration(ms)*time.Millisecond)
		if e != nil {
			return nil, e
		}
	}
	if embedding != "none" {
		v := resolver.Resolve(Values{})
		protocol := OpenAI
		module.EmbeddingName = "openAiEmbeddingModel"
		switch embedding {
		case "openai":
		case "ollama":
			protocol = Ollama
			module.EmbeddingName = "ollamaEmbeddingModel"
			v.BaseURL, err = get("spring.ai.ollama.base-url")
			if err != nil {
				return nil, err
			}
			v.EmbeddingModel, err = get("spring.ai.ollama.embedding.model")
			if err != nil {
				return nil, err
			}
		default:
			return nil, errors.New("尚未支持该嵌入模型provider")
		}
		delegate, e := NewEmbeddingClient(v, protocol, transport)
		if e != nil {
			return nil, e
		}
		raw, e := get("app.embedding-cache.enabled")
		if e != nil {
			return nil, e
		}
		enabled, e := strconv.ParseBool(raw)
		if e != nil {
			return nil, errors.New("嵌入缓存开关格式不合法")
		}
		raw, e = get("app.embedding-cache.capacity")
		if e != nil {
			return nil, e
		}
		capacity, e := strconv.Atoi(raw)
		if e != nil {
			return nil, errors.New("嵌入缓存容量格式不合法")
		}
		raw, e = get("app.embedding-cache.ttl")
		if e != nil {
			return nil, e
		}
		ttl, e := config.ParseDuration(raw)
		if e != nil {
			return nil, e
		}
		parts := []string{}
		for _, k := range []string{"spring.ai.model.embedding", "spring.ai.openai.base-url", "spring.ai.openai.embedding.model", "spring.ai.ollama.base-url", "spring.ai.ollama.embedding.model", "app.embedding-cache.revision"} {
			v, e := get(k)
			if e != nil {
				return nil, e
			}
			parts = append(parts, v)
		}
		identity := digest(module.EmbeddingName + "|" + strings.Join(parts, "|"))
		var shared SharedEmbeddingCache
		if module.Shared != nil {
			shared = module.Shared
		}
		module.Embedding, e = NewCachedEmbedding(delegate, identity, enabled, capacity, *ttl, shared)
		if e != nil {
			return nil, e
		}
	}
	module.Handler = NewHTTP(service, NewProbe(transport), NewAudioService(service.ReadService, transport), logger)
	return module, nil
}
func CheckPrivileges(ctx context.Context, pool *database.Pool) error {
	return pool.WithConn(ctx, func(c *sql.Conn) error {
		var allowed bool
		err := c.QueryRowContext(ctx, `SELECT bool_and(has_table_privilege(current_user,t,p)) FROM (VALUES ('public.ai_model_config'),('public.ai_model_profile'),('public.ai_model_profile_model')) tables(t) CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE')) privileges(p)`).Scan(&allowed)
		if err != nil {
			return err
		}
		var seq bool
		if err = c.QueryRowContext(ctx, `SELECT has_sequence_privilege(current_user,'public.ai_model_profile_model_id_seq','USAGE')`).Scan(&seq); err != nil {
			return err
		}
		if !allowed || !seq {
			return errors.New("P3运行账号缺少模型档案表或清单序列最小权限")
		}
		return nil
	})
}
