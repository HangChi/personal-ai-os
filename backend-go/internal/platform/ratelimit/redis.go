package ratelimit

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

// 与原 RedisRateBucket 的专用 150ms 边界一致；父请求更早的 deadline 优先。
const RedisAcquireTimeout = 150 * time.Millisecond

type RedisBucket struct {
	client *redisclient.Client
	key    string
}

var _ Bucket = (*RedisBucket)(nil)

func (bucket *RedisBucket) String() string   { return "RedisRateBucket{redacted}" }
func (bucket *RedisBucket) GoString() string { return bucket.String() }

func NewRedisBucket(client *redisclient.Client, namespace string) (*RedisBucket, error) {
	if client == nil {
		return nil, errors.New("Redis 限流必须显式提供专用客户端")
	}
	blank := strings.TrimFunc(namespace, func(r rune) bool {
		return (r >= '\t' && r <= '\r') || (r >= '\u001c' && r <= '\u001f') ||
			(r != '\u00a0' && r != '\u2007' && r != '\u202f' && (unicode.Is(unicode.Zs, r) || r == '\u2028' || r == '\u2029'))
	}) == ""
	if blank || !utf8.ValidString(namespace) {
		return nil, errors.New("Redis 限流命名空间无效")
	}
	hash := sha256.Sum256([]byte(namespace))
	return &RedisBucket{client: client, key: "personalos:rate:" + hex.EncodeToString(hash[:])}, nil
}

// 原 Java TAKE 文本块，只有缩进不同；单测直接与固定基线源文件对照。
// 保留 TIME、分数令牌、未来时间保护、配置不一致拒绝、拒绝也写状态和周期+1秒 TTL。
const redisTakeLua = `
local capacity = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local previous = redis.call('HMGET', KEYS[1], 'tokens', 'at', 'capacity', 'period')
if previous[3] and (previous[3] ~= ARGV[1] or previous[4] ~= ARGV[2]) then
    return redis.error_reply('Inconsistent rate limit configuration')
end
local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local at = tonumber(previous[2]) or now
now = math.max(now, at)
local tokens = math.min(capacity, (tonumber(previous[1]) or capacity) + (now-at)*capacity/period)
local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', now, 'capacity', ARGV[1], 'period', ARGV[2])
redis.call('PEXPIRE', KEYS[1], period + 1000)
return allowed
`

// Consume 的唯一资源名是共享策略使用的 model-rate；原 Java Redis 后端
// 只有一个命名空间桶，不支持把其他资源名意外混入同一预算。
// 脚本/网络错误返回 false + 脱敏 error，由 SharedLimiter 计错并拒绝。
func (bucket *RedisBucket) Consume(ctx context.Context, name string, capacity int, period time.Duration) (bool, error) {
	if name != "model-rate" {
		return false, errors.New("Redis 模型桶只支持 model-rate 资源")
	}
	if err := (Options{Capacity: capacity, Period: period}).Validate(); err != nil {
		return false, err
	}
	millis := max(int64(1), period.Milliseconds())
	op, cancel := context.WithTimeout(ctx, RedisAcquireTimeout)
	defer cancel()
	value, err := bucket.client.Eval(op, redisTakeLua, []string{bucket.key}, strconv.Itoa(capacity), strconv.FormatInt(millis, 10))
	if err != nil {
		return false, err
	}
	result, ok := value.(int64)
	if !ok || result < 0 || result > 1 {
		return false, errors.New("Redis 模型桶返回无效准入结果")
	}
	return result == 1, nil
}
