package knowledge

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"
	"sync/atomic"
	"time"
)

type RedisCacheClient interface {
	Eval(context.Context, string, []string, ...any) (any, error)
}
type CandidateCache struct {
	Store  *Store
	Client RedisCacheClient
	Prefix string
	Stats  *Stats
	closed atomic.Bool
}

// configuration 包含全部动态provider/model/cache/search配置；键中只保存哈希。
func NewCandidateCache(s *Store, client RedisCacheClient, namespace, configuration string) (*CandidateCache, error) {
	if s == nil || client == nil || strip(namespace) == "" {
		return nil, errors.New("候选缓存需要专用Redis与namespace")
	}
	p := s.Reader.Properties
	fingerprint, e := json.Marshal([]any{p.EmbeddingDimensions, p.SimilarityThreshold, p.HybridSearchEnabled, p.RRFK, p.KeywordChannelWeight})
	if e != nil {
		return nil, e
	}
	return &CandidateCache{Store: s, Client: client, Prefix: "personalos:rag:candidates:v1:" + digest(namespace+"\n"+configuration+"\n"+string(fingerprint)) + ":", Stats: NewStats()}, nil
}
func (c *CandidateCache) generation(ctx context.Context) (string, error) {
	var generation string
	e := c.Store.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, "SELECT generation::text FROM knowledge_candidate_generation WHERE id=1").Scan(&generation)
	})
	return generation, e
}
func (c *CandidateCache) count(result string) { c.Stats.Count("candidate.cache", result) }
func (c *CandidateCache) Get(ctx context.Context, query string, limit int, inTransaction bool, externalReady func() bool, load func(bool) ([]Hit, error)) ([]Hit, error) {
	if inTransaction || c.closed.Load() {
		return load(externalReady())
	}
	version, e := c.generation(ctx)
	if e != nil {
		c.count("read_error")
		return load(externalReady())
	}
	ready := externalReady()
	key := c.Prefix + version + ":" + digest(strconv.Itoa(limit)+"\n"+strconv.FormatBool(ready)+"\n"+query)
	op, cancel := context.WithTimeout(ctx, 250*time.Millisecond)
	raw, e := c.Client.Eval(op, `return redis.call('GET',KEYS[1])`, []string{key})
	cancel()
	if e != nil {
		c.count("read_error")
		return load(ready)
	}
	if text, ok := raw.(string); ok && len(text) <= 256*1024 {
		var hits []Hit
		if e = json.Unmarshal([]byte(text), &hits); e == nil {
			current, e := c.generation(ctx)
			if e == nil && current == version {
				c.count("hit")
				return hits, nil
			}
		} else {
			c.count("read_error")
			return load(ready)
		}
	}
	c.count("miss")
	hits, e := load(ready)
	if e != nil {
		return nil, e
	}
	payload, e := json.Marshal(hits)
	if e == nil && len(payload) <= 256*1024 {
		current, err := c.generation(ctx)
		if err == nil && current == version {
			op, stop := context.WithTimeout(ctx, 250*time.Millisecond)
			_, e = c.Client.Eval(op, `return redis.call('SETEX',KEYS[1],20,ARGV[1])`, []string{key}, string(payload))
			stop()
		} else {
			e = err
		}
	}
	if e != nil {
		c.count("write_error")
	}
	return hits, nil
}

// Close 关闭缓存功能；专用客户端由创建它的应用统一释放。
func (c *CandidateCache) Close() { c.closed.Store(true) }
