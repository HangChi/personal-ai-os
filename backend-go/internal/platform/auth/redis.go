package auth

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

// 只读 Go 自己的版本化键；不反序列化/修改 Spring Session 的原始内容。
type RedisStore struct {
	client        *redisclient.Client
	prefix        string
	timeoutMillis int64
}

func (store *RedisStore) String() string   { return "RedisSessionStore{redacted}" }
func (store *RedisStore) GoString() string { return store.String() }

func NewRedisStore(client *redisclient.Client, namespace string, timeout time.Duration) (*RedisStore, error) {
	if client == nil {
		return nil, errors.New("会话 Redis 客户端不能为空")
	}
	if namespace == "" || !utf8.ValidString(namespace) || len(namespace) > 256 || strings.IndexFunc(namespace, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) >= 0 {
		return nil, errors.New("会话 Redis 命名空间无效")
	}
	if timeout < time.Millisecond {
		return nil, errors.New("会话超时必须至少为 1 毫秒")
	}
	return &RedisStore{client: client, prefix: namespace + ":go:v1:session:", timeoutMillis: timeout.Milliseconds()}, nil
}

func (store *RedisStore) key(id string) string { return store.prefix + id }

type storedSession struct {
	Version   int    `json:"v"`
	Username  string `json:"username"`
	CSRFToken string `json:"csrfToken"`
}

const checkSessionLua = `
local raw = redis.call('GET', KEYS[1])
if not raw then return false end
local state = cjson.decode(raw)
if type(state) ~= 'table' or state.v ~= 1 or type(state.username) ~= 'string' or type(state.csrfToken) ~= 'string' then
    return redis.error_reply('Invalid Go session schema')
end
if redis.call('PTTL', KEYS[1]) <= 0 then return redis.error_reply('Invalid Go session expiry') end
`

const loadSessionLua = checkSessionLua + `
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return raw
`

const rotateSessionLua = `
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
if #KEYS == 2 then redis.call('DEL', KEYS[2]) end
return 1
`

const csrfSessionLua = checkSessionLua + `
if state.csrfToken == '' then
    state.csrfToken = ARGV[1]
    raw = cjson.encode(state)
    redis.call('SET', KEYS[1], raw, 'XX', 'KEEPTTL')
end
return raw
`

func decodeSession(value any) (Session, bool, error) {
	if value == nil {
		return Session{}, false, nil
	}
	raw, ok := value.(string)
	if !ok {
		return Session{}, false, errors.New("会话 Redis 响应类型无效")
	}
	var state storedSession
	if !utf8.ValidString(raw) || json.Unmarshal([]byte(raw), &state) != nil || state.Version != 1 {
		return Session{}, false, errors.New("会话 Redis 内容无效")
	}
	return Session{Username: state.Username, CSRFToken: state.CSRFToken}, true, nil
}

func (store *RedisStore) Load(ctx context.Context, id string) (Session, bool, error) {
	if !validSessionID(id) {
		return Session{}, false, errors.New("会话 ID 无效")
	}
	value, err := store.client.Eval(ctx, loadSessionLua, []string{store.key(id)}, store.timeoutMillis)
	if err != nil {
		return Session{}, false, err
	}
	return decodeSession(value)
}

func (store *RedisStore) Rotate(ctx context.Context, oldID, newID string, state Session) error {
	if !validSessionID(newID) || oldID != "" && !validSessionID(oldID) || oldID == newID {
		return errors.New("会话轮换 ID 无效")
	}
	keys := []string{store.key(newID)}
	if oldID != "" {
		keys = append(keys, store.key(oldID))
	}
	raw, err := json.Marshal(storedSession{Version: 1, Username: state.Username, CSRFToken: state.CSRFToken})
	if err != nil {
		return errors.New("会话序列化失败")
	}
	value, err := store.client.Eval(ctx, rotateSessionLua, keys, string(raw), store.timeoutMillis)
	if err != nil {
		return err
	}
	result, ok := value.(int64)
	if !ok {
		return errors.New("会话 Redis 轮换响应无效")
	}
	if result == 0 {
		return ErrSessionCollision
	}
	if result != 1 {
		return errors.New("会话 Redis 轮换状态无效")
	}
	return nil
}

func (store *RedisStore) EnsureCSRF(ctx context.Context, id, token string) (Session, bool, error) {
	if !validSessionID(id) || token == "" {
		return Session{}, false, errors.New("会话或 CSRF 令牌无效")
	}
	value, err := store.client.Eval(ctx, csrfSessionLua, []string{store.key(id)}, token)
	if err != nil {
		return Session{}, false, err
	}
	return decodeSession(value)
}

func (store *RedisStore) Delete(ctx context.Context, id string) error {
	if !validSessionID(id) {
		return errors.New("会话 ID 无效")
	}
	_, err := store.client.Eval(ctx, `return redis.call('DEL', KEYS[1])`, []string{store.key(id)})
	return err
}
