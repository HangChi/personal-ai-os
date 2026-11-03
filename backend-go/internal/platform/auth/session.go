package auth

import (
	"context"
	"errors"
	"sync"
	"time"
)

const DefaultSessionTimeout = 30 * time.Minute

var ErrSessionCollision = errors.New("会话 ID 冲突")

// Session 是存储层返回的值副本，不包含凭据或可执行的安全上下文对象。
// Username 为空代表匿名会话；CSRF 可以在登录前创建匿名会话。
type Session struct {
	Username  string `json:"username"`
	CSRFToken string `json:"csrfToken"`
}

func (state Session) String() string   { return "Session{redacted}" }
func (state Session) GoString() string { return state.String() }

// SessionStore 必须原子实现轮换和令牌首次写入。
// Load 读取且续期；EnsureCSRF 不得复活过期/已删除的 ID。
// Rotate 只能在确认新 ID 未占用后删除旧 ID，且不保留旧会话属性。
type SessionStore interface {
	Load(context.Context, string) (Session, bool, error)
	Rotate(context.Context, string, string, Session) error
	EnsureCSRF(context.Context, string, string) (Session, bool, error)
	Delete(context.Context, string) error
}

type sessionEntry struct {
	state   Session
	expires time.Time
}

// MemoryStore 对应单进程容器会话；不用于替代已启用的共享 Redis。
// 没有后台 goroutine，定期在请求操作中清理过期条目。
type MemoryStore struct {
	mu        sync.Mutex
	entries   map[string]sessionEntry
	timeout   time.Duration
	now       func() time.Time
	nextSweep time.Time
}

func (store *MemoryStore) String() string   { return "MemorySessionStore{redacted}" }
func (store *MemoryStore) GoString() string { return store.String() }

func NewMemoryStore(timeout time.Duration) (*MemoryStore, error) {
	return newMemoryStore(timeout, time.Now)
}

func newMemoryStore(timeout time.Duration, now func() time.Time) (*MemoryStore, error) {
	if timeout <= 0 {
		return nil, errors.New("会话超时必须为正")
	}
	return &MemoryStore{entries: make(map[string]sessionEntry), timeout: timeout, now: now}, nil
}

func validSessionID(id string) bool {
	if len(id) != 64 {
		return false
	}
	for _, char := range id {
		if !(char >= '0' && char <= '9' || char >= 'a' && char <= 'f') {
			return false
		}
	}
	return true
}

func (store *MemoryStore) live(id string, now time.Time) (sessionEntry, bool) {
	if !now.Before(store.nextSweep) {
		for key, entry := range store.entries {
			if !entry.expires.After(now) {
				delete(store.entries, key)
			}
		}
		store.nextSweep = now.Add(time.Minute)
	}
	entry, ok := store.entries[id]
	if ok && !entry.expires.After(now) {
		delete(store.entries, id)
		return sessionEntry{}, false
	}
	return entry, ok
}

func (store *MemoryStore) Load(ctx context.Context, id string) (Session, bool, error) {
	if err := ctx.Err(); err != nil {
		return Session{}, false, err
	}
	if !validSessionID(id) {
		return Session{}, false, errors.New("会话 ID 无效")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return Session{}, false, err
	}
	now := store.now()
	entry, ok := store.live(id, now)
	if ok {
		entry.expires = now.Add(store.timeout)
		store.entries[id] = entry
	}
	return entry.state, ok, nil
}

func (store *MemoryStore) Rotate(ctx context.Context, oldID, newID string, state Session) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !validSessionID(newID) || oldID != "" && !validSessionID(oldID) || oldID == newID {
		return errors.New("会话轮换 ID 无效")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	now := store.now()
	if _, ok := store.live(newID, now); ok {
		return ErrSessionCollision
	}
	delete(store.entries, oldID)
	store.entries[newID] = sessionEntry{state: state, expires: now.Add(store.timeout)}
	return nil
}

func (store *MemoryStore) EnsureCSRF(ctx context.Context, id, token string) (Session, bool, error) {
	if err := ctx.Err(); err != nil {
		return Session{}, false, err
	}
	if !validSessionID(id) || token == "" {
		return Session{}, false, errors.New("会话或 CSRF 令牌无效")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return Session{}, false, err
	}
	entry, ok := store.live(id, store.now())
	if ok && entry.state.CSRFToken == "" {
		entry.state.CSRFToken = token
		store.entries[id] = entry
	}
	return entry.state, ok, nil
}

func (store *MemoryStore) Delete(ctx context.Context, id string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !validSessionID(id) {
		return errors.New("会话 ID 无效")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	delete(store.entries, id)
	return nil
}
