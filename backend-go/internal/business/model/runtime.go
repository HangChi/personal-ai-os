package model

import (
	"context"
	"errors"
	"net/http"
	"sync"

	"personal-ai-os/backend-go/internal/platform/api"
)

type ClientFactory func(Resolved) (*ChatClient, error)

// Runtime 原子替换模型引用；在途请求继续持有旧模型；命名模型缓存按代失效。
type Runtime struct {
	mu         sync.RWMutex
	namedMu    sync.Mutex
	current    *ChatClient
	generation uint64
	named      map[string]*ChatClient
	factory    ClientFactory
	resolver   func(context.Context, string) (*ChatClient, error)
	Evidence   *WindowEvidence
}

func NewRuntime(transport http.RoundTripper) *Runtime {
	return &Runtime{named: map[string]*ChatClient{}, factory: func(v Resolved) (*ChatClient, error) { return NewChatClient(v, OpenAI, transport) }, Evidence: NewWindowEvidence()}
}
func (r *Runtime) Register(resolver func(context.Context, string) (*ChatClient, error)) {
	r.mu.Lock()
	r.resolver = resolver
	r.generation++
	r.named = map[string]*ChatClient{}
	r.Evidence.Reset()
	r.mu.Unlock()
}
func (r *Runtime) Configured() bool     { r.mu.RLock(); defer r.mu.RUnlock(); return r.current != nil }
func (r *Runtime) Current() *ChatClient { r.mu.RLock(); defer r.mu.RUnlock(); return r.current }
func (r *Runtime) Replace(c *ChatClient) {
	r.mu.Lock()
	r.current = c
	r.generation++
	r.named = map[string]*ChatClient{}
	r.Evidence.Reset()
	r.mu.Unlock()
}
func (r *Runtime) Apply(v Resolved) error {
	if !v.HasAPIKey() {
		return nil
	}
	c, err := r.factory(v)
	if err != nil {
		return api.NewBusinessError(api.AIUpstreamError, "配置已保存，但按该配置初始化模型失败：端点、凭据或模型名格式不合法")
	}
	r.Replace(c)
	return nil
}
func (r *Runtime) Target(ctx context.Context, name string) (*ChatClient, error) {
	name = strip(name)
	for attempt := 0; attempt < 2; attempt++ {
		r.mu.RLock()
		current, generation, resolve := r.current, r.generation, r.resolver
		r.mu.RUnlock()
		if current == nil {
			return nil, api.NewBusinessError(api.AINotConfigured, api.AINotConfigured.Message())
		}
		if name == "" || current.Name() == name {
			return current, nil
		}
		r.namedMu.Lock()
		r.mu.RLock()
		cached := r.named[name]
		same := generation == r.generation
		r.mu.RUnlock()
		if !same {
			r.namedMu.Unlock()
			continue
		}
		if cached != nil {
			r.namedMu.Unlock()
			return cached, nil
		}
		if resolve == nil {
			r.namedMu.Unlock()
			return current, nil
		}
		built, err := resolve(ctx, name)
		if err != nil {
			r.namedMu.Unlock()
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return current, nil
		}
		r.mu.Lock()
		same = generation == r.generation
		if same {
			if len(r.named) >= 32 {
				r.named = map[string]*ChatClient{}
			}
			r.named[name] = built
		}
		r.mu.Unlock()
		r.namedMu.Unlock()
		if same {
			return built, nil
		}
	}
	c := r.Current()
	if c == nil {
		return nil, errors.New("聊天模型尚未装配")
	}
	return c, nil
}

// TargetSnapshot 将客户端和证据代配对，热切换中的旧请求不能污染新端点证据。
func (r *Runtime) TargetSnapshot(ctx context.Context, name string) (*ChatClient, uint64, error) {
	for attempt := 0; attempt < 3; attempt++ {
		epoch := r.Evidence.Generation()
		client, e := r.Target(ctx, name)
		if e != nil {
			return nil, 0, e
		}
		if epoch == r.Evidence.Generation() {
			return client, epoch, nil
		}
	}
	return nil, 0, errors.New("模型配置正在切换，请稍后重试")
}
