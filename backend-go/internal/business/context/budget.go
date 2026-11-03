package chatcontext

import (
	"context"
	"errors"
	"math"
	"strings"
	"sync"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

type PreferenceStore interface {
	Load(context.Context) (*int, error)
	Save(context.Context, int) error
}
type Window struct {
	ModelName string
	Tokens    int
	Source    string
}
type Budget struct {
	mu           sync.RWMutex
	selected     int
	AI           config.AIProperties
	Protection   config.ContextProtectionProperties
	store        PreferenceStore
	lookup       func(context.Context, string) *int
	defaultModel func() string
	fixed        func() int
	Evidence     *model.WindowEvidence
}

func NewBudget(ctx context.Context, ai config.AIProperties, protection config.ContextProtectionProperties, store PreferenceStore, lookup func(context.Context, string) *int, defaultModel func() string, evidence *model.WindowEvidence, fixed func() int) (*Budget, error) {
	if ai.Memory.MaxMessages <= 0 || ai.Memory.ContextWindowTokens <= 0 || protection.OutputReserveTokens <= 0 || protection.ImageReserveTokens <= 0 || protection.SafetyMarginTokens <= 0 || protection.MaxRetries < 0 {
		return nil, errors.New("上下文配置无效")
	}
	if evidence == nil {
		evidence = model.NewWindowEvidence()
	}
	b := &Budget{selected: min(32768, ai.Memory.ContextWindowTokens), AI: ai, Protection: protection, store: store, lookup: lookup, defaultModel: defaultModel, Evidence: evidence, fixed: fixed}
	if store != nil {
		saved, err := store.Load(ctx)
		if err != nil {
			return nil, err
		}
		if saved != nil {
			b.selected = *saved
		}
	}
	return b, nil
}
func (b *Budget) Selected() int { b.mu.RLock(); defer b.mu.RUnlock(); return b.selected }
func (b *Budget) Set(ctx context.Context, n int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	n = max(1, n)
	if b.store != nil {
		if err := b.store.Save(ctx, n); err != nil {
			return err
		}
	}
	b.selected = n
	return nil
}
func (b *Budget) Fixed() int {
	if b.fixed == nil {
		return 0
	}
	return max(0, b.fixed())
}
func (b *Budget) Name(name string) string {
	if strings.TrimSpace(name) != "" {
		return strings.TrimSpace(name)
	}
	if b.defaultModel != nil {
		return strings.TrimSpace(b.defaultModel())
	}
	return ""
}
func (b *Budget) Window(ctx context.Context, name string) Window {
	name = b.Name(name)
	w := Window{name, b.AI.Memory.ContextWindowTokens, "fallback"}
	if name != "" {
		if known := model.LookupKnownWindow(name); known != nil {
			w.Tokens, w.Source = *known, "catalog"
		}
		if b.lookup != nil {
			if configured := b.lookup(ctx, name); configured != nil && *configured >= 1024 {
				w.Tokens, w.Source = *configured, "configured"
			}
		}
	}
	observed := b.Evidence.Snapshot(b.Evidence.Generation(), name)
	if observed.ContextTokens != nil && *observed.ContextTokens <= w.Tokens {
		w.Tokens, w.Source = *observed.ContextTokens, "endpoint"
	}
	return w
}
func (b *Budget) Margin(window int) int {
	return min(b.Protection.SafetyMarginTokens, max(1, window/16))
}
func (b *Budget) Output(window, thinking int) int {
	base := min(b.Protection.OutputReserveTokens, max(1, window/4))
	if thinking > 0 {
		base = max(base, thinking+1024)
	}
	return min(base, max(1, window-b.Margin(window)-1))
}
func (b *Budget) ForWindow(window int) int {
	return min(max(b.Selected(), b.Fixed()+1024), max(1, window-b.Output(window, 0)-b.Margin(window)))
}
func (b *Budget) InputLimit(ctx context.Context, name string, output int) int {
	w := b.Window(ctx, name)
	limit := max(1, w.Tokens-output-b.Margin(w.Tokens))
	snapshot := b.Evidence.Snapshot(b.Evidence.Generation(), w.ModelName)
	if snapshot.InputTokens != nil {
		reserve := b.Margin(w.Tokens)
		if snapshot.ExplicitInputLimit {
			reserve += output
		}
		limit = min(limit, max(1, *snapshot.InputTokens-reserve))
	}
	return limit
}
func (b *Budget) ForModel(ctx context.Context, name string) int {
	w := b.Window(ctx, name)
	return min(b.ForWindow(w.Tokens), b.InputLimit(ctx, name, b.Output(w.Tokens, 0)))
}
func (b *Budget) CharBudget(ctx context.Context, name string) int {
	return int(math.Floor(float64(max(b.ForModel(ctx, name)-b.Fixed(), 1024))*1.6 + .5))
}
func (b *Budget) InputSource(ctx context.Context, name string) string {
	s := b.Evidence.Snapshot(b.Evidence.Generation(), b.Name(name))
	if s.InputTokens != nil {
		if s.ExplicitInputLimit {
			return "endpoint"
		}
		return "adaptive"
	}
	return b.Window(ctx, name).Source
}
func TooLarge() error {
	return api.NewBusinessError(api.ContextTooLarge, api.ContextTooLarge.Message())
}

func (b *Budget) Count(request model.ChatRequest, name string, epoch uint64) int {
	n := CountPrompt(request, name, b.Protection.ImageReserveTokens)
	ratio := b.Evidence.Snapshot(epoch, name).TokenRatio
	return int(min(float64(math.MaxInt32), math.Ceil(float64(n)*ratio)))
}

// Prepare 仅裁剪请求副本，绝不把上下文窗口写回持久化历史。
func (b *Budget) Prepare(ctx context.Context, original model.ChatRequest, name string, epoch uint64, attempt, previousCount int) (model.ChatRequest, error) {
	name = b.Name(name)
	window := b.Window(ctx, name).Tokens
	output := b.Output(window, 0)
	if original.MaxTokens != nil && *original.MaxTokens > 0 {
		output = min(output, *original.MaxTokens)
	}
	thinking := 0
	if v, ok := original.ExtraBody["thinking_budget"].(int); ok {
		thinking = v
	}
	if thinking > 0 {
		output = max(output, thinking+1024)
		if output+b.Margin(window) >= window {
			return model.ChatRequest{}, TooLarge()
		}
	}
	cap := min(b.ForModel(ctx, name), b.InputLimit(ctx, name, output))
	cap = max(1, int(float64(cap)*math.Pow(.65, float64(attempt))))
	if previousCount > 0 {
		cap = min(cap, max(1, int(float64(previousCount)*.65)))
	}
	candidate := original
	candidate.MaxTokens = &output
	candidate.Messages = append([]model.Message(nil), original.Messages...)
	for b.Count(candidate, name, epoch) > cap && removeOldestTurn(&candidate.Messages) {
	}
	for chars := max(64, cap/2); b.Count(candidate, name, epoch) > cap; chars = max(64, chars/2) {
		changed := false
		for i, m := range candidate.Messages {
			if m.Role == "tool" && JavaLength(m.Content) > chars {
				m.Content = JavaSubstring(m.Content, 0, chars) + "\n[工具结果因上下文预算截断，请按需缩小范围重新查询]"
				candidate.Messages[i] = m
				changed = true
			}
		}
		if !changed || chars == 64 {
			break
		}
	}
	if b.Count(candidate, name, epoch) > cap {
		return model.ChatRequest{}, TooLarge()
	}
	return candidate, nil
}
func removeOldestTurn(messages *[]model.Message) bool {
	ms := *messages
	last := -1
	for i, m := range ms {
		if m.Role == "user" {
			last = i
		}
	}
	first := -1
	for i := 0; i < last; i++ {
		if ms[i].Role != "system" {
			first = i
			break
		}
	}
	if first < 0 {
		return false
	}
	end := first + 1
	for end < last && ms[end].Role != "user" {
		end++
	}
	kept := make([]model.Message, 0, len(ms))
	for i, m := range ms {
		if i < first || i >= end || m.Role == "system" {
			kept = append(kept, m)
		}
	}
	*messages = kept
	return true
}

// GuardStream 只有在尚未向调用者交付任何上游块时才允许上下文缩小重试。
func (b *Budget) GuardStream(ctx context.Context, client *model.ChatClient, request model.ChatRequest, epoch uint64, before func() error, receive func(model.ChatChunk) error) (model.ChatResult, error) {
	previous := 0
	name := client.Name()
	for attempt := 0; ; attempt++ {
		prepared, err := b.Prepare(ctx, request, name, epoch, attempt, previous)
		if err != nil {
			return model.ChatResult{}, err
		}
		count := b.Count(prepared, name, epoch)
		if previous > 0 && count >= previous {
			return model.ChatResult{}, TooLarge()
		}
		if before != nil {
			if err = before(); err != nil {
				return model.ChatResult{}, err
			}
		}
		emitted := false
		result, err := client.Stream(ctx, prepared, func(chunk model.ChatChunk) error { emitted = true; return receive(chunk) })
		if err == nil {
			estimate := CountPrompt(prepared, name, b.Protection.ImageReserveTokens)
			for _, m := range prepared.Messages {
				if len(m.Images) > 0 {
					estimate = 0
					break
				}
			}
			b.Evidence.Accepted(epoch, name, estimate, result.Usage.PromptTokens)
			return result, nil
		}
		if !model.IsOverflow(err) {
			return result, err
		}
		b.Evidence.Rejected(epoch, name, err, count)
		if emitted || attempt >= b.Protection.MaxRetries {
			return result, TooLarge()
		}
		previous = count
	}
}
