package model

import (
	"bytes"
	"container/list"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"sync"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

type EmbeddingRequest struct {
	Input       []string
	Options     map[string]any
	OptionsType string
}
type EmbeddingResult struct {
	Index  int
	Vector []float32
}
type EmbeddingResponse struct {
	Results []EmbeddingResult
	Usage   Usage
	Model   string
}
type EmbeddingModel interface {
	Embed(context.Context, EmbeddingRequest) (EmbeddingResponse, error)
}
type EmbeddingClient struct {
	resolved Resolved
	protocol Protocol
	http     *http.Client
	timeout  time.Duration
}

func NewEmbeddingClient(v Resolved, p Protocol, transport http.RoundTripper) (*EmbeddingClient, error) {
	check := v
	check.ChatModel = v.EmbeddingModel
	if _, err := NewChatClient(check, p, transport); err != nil {
		return nil, err
	}
	return &EmbeddingClient{v, p, httpClient(transport), 120 * time.Second}, nil
}
func (*EmbeddingClient) String() string     { return "EmbeddingClient{redacted}" }
func (c *EmbeddingClient) GoString() string { return c.String() }
func (c *EmbeddingClient) Embed(ctx context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	body := map[string]any{"model": c.resolved.EmbeddingModel, "input": r.Input}
	for k, v := range r.Options {
		body[k] = v
	}
	data, err := json.Marshal(body)
	if err != nil {
		return EmbeddingResponse{}, errors.New("嵌入请求无法序列化")
	}
	address := endpoint(c.resolved.BaseURL, "/embeddings")
	if c.protocol == Ollama {
		address = rootEndpoint(c.resolved.BaseURL) + "/api/embed"
	}
	req, err := http.NewRequestWithContext(ctx, "POST", address, bytes.NewReader(data))
	if err != nil {
		return EmbeddingResponse{}, errors.New("嵌入端点格式不合法")
	}
	req.GetBody = nil
	req.Header.Set("Content-Type", "application/json")
	if c.resolved.HasAPIKey() {
		req.Header.Set("Authorization", "Bearer "+c.resolved.APIKey())
	}
	resp, err := c.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return EmbeddingResponse{}, ctx.Err()
		}
		return EmbeddingResponse{}, errors.New("嵌入端点连接失败")
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return EmbeddingResponse{}, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return EmbeddingResponse{}, &UpstreamError{resp.StatusCode, string(raw)}
	}
	var v struct {
		Model string `json:"model"`
		Usage Usage  `json:"usage"`
		Data  []struct {
			Index  int       `json:"index"`
			Vector []float32 `json:"embedding"`
		} `json:"data"`
		Embeddings [][]float32 `json:"embeddings"`
		Prompt     int         `json:"prompt_eval_count"`
	}
	if err = json.Unmarshal(raw, &v); err != nil {
		return EmbeddingResponse{}, errors.New("嵌入响应格式不合法")
	}
	out := EmbeddingResponse{Usage: v.Usage, Model: v.Model, Results: []EmbeddingResult{}}
	if c.protocol == Ollama {
		for i, vec := range v.Embeddings {
			out.Results = append(out.Results, EmbeddingResult{i, vec})
		}
		out.Usage = Usage{PromptTokens: v.Prompt, TotalTokens: v.Prompt}
	} else {
		for _, v := range v.Data {
			out.Results = append(out.Results, EmbeddingResult{v.Index, v.Vector})
		}
	}
	if _, err = validatedVectors(out, len(r.Input)); err != nil {
		return EmbeddingResponse{}, err
	}
	return out, nil
}

type SharedEmbeddingCache interface {
	Get(context.Context, string, string) []float32
	Put(context.Context, string, string, []float32, time.Duration)
}
type CacheStats struct {
	mu       sync.Mutex
	Counts   map[string]uint64
	Calls    uint64
	Duration time.Duration
}
type CacheSnapshot struct {
	Counts   map[string]uint64
	Calls    uint64
	Duration time.Duration
}

func NewCacheStats() *CacheStats          { return &CacheStats{Counts: map[string]uint64{}} }
func (s *CacheStats) count(result string) { s.mu.Lock(); s.Counts[result]++; s.mu.Unlock() }
func (s *CacheStats) record(elapsed time.Duration) {
	s.mu.Lock()
	s.Calls++
	s.Duration += elapsed
	s.mu.Unlock()
}
func (s *CacheStats) Snapshot() CacheSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := map[string]uint64{}
	for k, v := range s.Counts {
		m[k] = v
	}
	return CacheSnapshot{m, s.Calls, s.Duration}
}

type cacheEntry struct {
	key     string
	vector  []float32
	expires time.Time
}
type localVectors struct {
	mu       sync.Mutex
	capacity int
	ttl      time.Duration
	items    map[string]*list.Element
	order    *list.List
	now      func() time.Time
}

func newLocal(capacity int, ttl time.Duration) *localVectors {
	return &localVectors{capacity: capacity, ttl: ttl, items: map[string]*list.Element{}, order: list.New(), now: time.Now}
}
func (c *localVectors) get(key string) ([]float32, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	el := c.items[key]
	if el == nil {
		return nil, false
	}
	v := el.Value.(cacheEntry)
	if !c.now().Before(v.expires) {
		delete(c.items, key)
		c.order.Remove(el)
		return nil, false
	}
	c.order.MoveToFront(el)
	return append([]float32(nil), v.vector...), true
}
func (c *localVectors) put(key string, vector []float32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el := c.items[key]; el != nil {
		c.order.Remove(el)
		delete(c.items, key)
	}
	for len(c.items) >= c.capacity {
		el := c.order.Back()
		delete(c.items, el.Value.(cacheEntry).key)
		c.order.Remove(el)
	}
	c.items[key] = c.order.PushFront(cacheEntry{key, append([]float32(nil), vector...), c.now().Add(c.ttl)})
}

type CachedEmbedding struct {
	delegate      EmbeddingModel
	shared        SharedEmbeddingCache
	identity      string
	enabled       bool
	ttl           time.Duration
	vectors, seen *localVectors
	Stats         *CacheStats
}

func NewCachedEmbedding(delegate EmbeddingModel, identity string, enabled bool, capacity int, ttl time.Duration, shared SharedEmbeddingCache) (*CachedEmbedding, error) {
	if delegate == nil || capacity < 1 || ttl <= 0 {
		return nil, errors.New("嵌入缓存容量和 TTL 必须为正数")
	}
	return &CachedEmbedding{delegate, shared, identity, enabled, ttl, newLocal(capacity, ttl), newLocal(capacity, ttl), NewCacheStats()}, nil
}
func (*CachedEmbedding) String() string     { return "CachedEmbedding{redacted}" }
func (c *CachedEmbedding) GoString() string { return c.String() }
func digest(s string) string                { sum := sha256.Sum256([]byte(s)); return hex.EncodeToString(sum[:]) }
func (c *CachedEmbedding) invoke(ctx context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
	start := time.Now()
	defer func() { c.Stats.record(time.Since(start)) }()
	return c.delegate.Embed(ctx, r)
}
func validatedVectors(r EmbeddingResponse, count int) (map[int][]float32, error) {
	m := map[int][]float32{}
	for _, v := range r.Results {
		if v.Index < 0 || v.Index >= count || len(v.Vector) == 0 || len(v.Vector) > 65536 {
			return nil, errors.New("嵌入结果为空或索引非法")
		}
		if _, ok := m[v.Index]; ok {
			return nil, errors.New("嵌入结果索引重复")
		}
		for _, f := range v.Vector {
			if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
				return nil, errors.New("嵌入结果不是有限数值")
			}
		}
		m[v.Index] = v.Vector
	}
	if len(m) != count {
		return nil, errors.New("嵌入结果数量不匹配")
	}
	return m, nil
}
func (c *CachedEmbedding) Embed(ctx context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
	if err := ctx.Err(); err != nil {
		return EmbeddingResponse{}, err
	}
	options := r.Options
	if options == nil {
		options = map[string]any{}
	}
	raw, err := json.Marshal(options)
	if err != nil {
		c.Stats.count("bypass")
		return c.invoke(ctx, r)
	}
	keys := make([]string, len(r.Input))
	for i, text := range r.Input {
		keys[i] = digest(r.OptionsType + string(raw) + "\n" + text)
	}
	if !c.enabled {
		for _, key := range keys {
			if _, ok := c.seen.get(key); ok {
				c.Stats.count("observed_repeat")
			} else {
				c.Stats.count("observed_new")
				c.seen.put(key, nil)
			}
		}
		return c.invoke(ctx, r)
	}
	outputs := make([][]float32, len(keys))
	missing := []string{}
	missingKeys := map[string]int{}
	for i, key := range keys {
		vector, ok := c.vectors.get(key)
		outcome := "hit"
		if !ok && c.shared != nil {
			vector = c.shared.Get(ctx, c.identity, key)
			if vector != nil {
				ok = true
				outcome = "shared_hit"
				c.vectors.put(key, vector)
			}
		}
		if !ok {
			outcome = "miss"
			if _, exists := missingKeys[key]; !exists {
				missingKeys[key] = len(missing)
				missing = append(missing, r.Input[i])
			}
		}
		outputs[i] = vector
		c.Stats.count(outcome)
	}
	var response EmbeddingResponse
	computed := map[int][]float32{}
	if len(missing) > 0 {
		response, err = c.invoke(ctx, EmbeddingRequest{missing, r.Options, r.OptionsType})
		if err != nil {
			return EmbeddingResponse{}, err
		}
		computed, err = validatedVectors(response, len(missing))
		if err != nil {
			return EmbeddingResponse{}, err
		}
		if err = ctx.Err(); err != nil {
			return EmbeddingResponse{}, err
		}
		for key, index := range missingKeys {
			v := computed[index]
			c.vectors.put(key, v)
			if c.shared != nil {
				c.shared.Put(ctx, c.identity, key, v, c.ttl)
			}
		}
	}
	out := EmbeddingResponse{Usage: response.Usage, Model: response.Model, Results: []EmbeddingResult{}}
	for i, key := range keys {
		v := outputs[i]
		if v == nil {
			v = computed[missingKeys[key]]
		}
		out.Results = append(out.Results, EmbeddingResult{i, append([]float32(nil), v...)})
	}
	return out, nil
}

type RedisEmbeddingCache struct {
	client  *redisclient.Client
	prefix  string
	timeout time.Duration
	Stats   *CacheStats
}

func NewRedisEmbeddingCache(client *redisclient.Client, namespace string, timeout time.Duration) (*RedisEmbeddingCache, error) {
	if client == nil || strip(namespace) == "" || timeout < time.Millisecond || timeout > 5*time.Second {
		return nil, errors.New("Redis缓存需要专用客户端、namespace及有效超时")
	}
	return &RedisEmbeddingCache{client, "personalos:embedding:" + digest(namespace) + ":", timeout, NewCacheStats()}, nil
}
func (c *RedisEmbeddingCache) key(identity, key string) string {
	return c.prefix + digest(identity) + ":" + key
}
func (c *RedisEmbeddingCache) Get(ctx context.Context, identity, key string) []float32 {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	result, err := c.client.Eval(ctx, `return redis.call('GET',KEYS[1])`, []string{c.key(identity, key)})
	if err != nil {
		c.Stats.count("read_error")
		return nil
	}
	if result == nil {
		return nil
	}
	s, ok := result.(string)
	if !ok {
		c.Stats.count("read_error")
		return nil
	}
	var v []float32
	if err = json.Unmarshal([]byte(s), &v); err != nil {
		c.Stats.count("read_error")
		return nil
	}
	if _, err = validatedVectors(EmbeddingResponse{Results: []EmbeddingResult{{0, v}}}, 1); err != nil {
		return nil
	}
	return v
}
func (c *RedisEmbeddingCache) Put(ctx context.Context, identity, key string, vector []float32, ttl time.Duration) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	raw, err := json.Marshal(vector)
	if err == nil {
		_, err = c.client.Eval(ctx, `return redis.call('PSETEX',KEYS[1],ARGV[1],ARGV[2])`, []string{c.key(identity, key)}, ttl.Milliseconds(), string(raw))
	}
	if err != nil {
		c.Stats.count("write_error")
	}
}
