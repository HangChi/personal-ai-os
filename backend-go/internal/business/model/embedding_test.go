package model

import (
	"context"
	"errors"
	"math"
	"reflect"
	"testing"
	"time"
)

type embeddingFunc func(context.Context, EmbeddingRequest) (EmbeddingResponse, error)

func (f embeddingFunc) Embed(ctx context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
	return f(ctx, r)
}
func TestEmbeddingCacheParityOrderOptionsCloneTTLAndCapacity(t *testing.T) {
	calls := 0
	f := embeddingFunc(func(_ context.Context, r EmbeddingRequest) (EmbeddingResponse, error) {
		calls++
		out := EmbeddingResponse{Usage: Usage{TotalTokens: len(r.Input)}}
		for i := len(r.Input) - 1; i >= 0; i-- {
			out.Results = append(out.Results, EmbeddingResult{i, []float32{float32(len(r.Input[i]))}})
		}
		return out, nil
	})
	ctx := context.Background()
	observed, _ := NewCachedEmbedding(f, "model", false, 10, time.Minute, nil)
	_, _ = observed.Embed(ctx, EmbeddingRequest{Input: []string{"重复"}})
	_, _ = observed.Embed(ctx, EmbeddingRequest{Input: []string{"重复"}})
	if calls != 2 || observed.Stats.Snapshot().Counts["observed_repeat"] != 1 {
		t.Fatal("observation changed calls")
	}
	calls = 0
	c, _ := NewCachedEmbedding(f, "model", true, 2, time.Minute, nil)
	now := time.Now()
	c.vectors.now = func() time.Time { return now }
	request := EmbeddingRequest{Input: []string{"a", "bb", "a"}}
	first, e := c.Embed(ctx, request)
	if e != nil || calls != 1 || first.Usage.TotalTokens != 2 {
		t.Fatal(first, e, calls)
	}
	want := []EmbeddingResult{{0, []float32{1}}, {1, []float32{2}}, {2, []float32{1}}}
	if !reflect.DeepEqual(first.Results, want) {
		t.Fatal(first.Results)
	}
	first.Results[0].Vector[0] = 99
	again, e := c.Embed(ctx, request)
	if e != nil || again.Results[0].Vector[0] != 1 || calls != 1 || again.Usage.TotalTokens != 0 {
		t.Fatal("cache alias/metadata", again, e)
	}
	_, _ = c.Embed(ctx, EmbeddingRequest{Input: []string{"a"}, Options: map[string]any{"model": "other"}})
	if calls != 2 {
		t.Fatal("options not isolated")
	}
	_, _ = c.Embed(ctx, EmbeddingRequest{Input: []string{"ccc"}})
	_, _ = c.Embed(ctx, request)
	if calls < 4 {
		t.Fatal("capacity not bounded")
	}
	now = now.Add(time.Minute)
	_, _ = c.Embed(ctx, request)
	if c.Stats.Snapshot().Counts["miss"] < 5 {
		t.Fatal("ttl not applied")
	}
}
func TestEmbeddingInvalidBatchNeverPartiallyCachesAndFailuresRetry(t *testing.T) {
	for _, bad := range []EmbeddingResponse{{Results: []EmbeddingResult{{0, []float32{1}}}}, {Results: []EmbeddingResult{{0, []float32{1}}, {0, []float32{2}}}}, {Results: []EmbeddingResult{{0, []float32{1}}, {1, nil}}}, {Results: []EmbeddingResult{{0, []float32{1}}, {1, []float32{float32(math.NaN())}}}}} {
		calls := 0
		f := embeddingFunc(func(context.Context, EmbeddingRequest) (EmbeddingResponse, error) { calls++; return bad, nil })
		c, _ := NewCachedEmbedding(f, "id", true, 10, time.Minute, nil)
		for i := 0; i < 2; i++ {
			if _, e := c.Embed(context.Background(), EmbeddingRequest{Input: []string{"a", "b"}}); e == nil {
				t.Fatal("cached invalid batch")
			}
		}
		if calls != 2 || len(c.vectors.items) != 0 {
			t.Fatal("invalid batch partially cached")
		}
	}
	calls := 0
	f := embeddingFunc(func(context.Context, EmbeddingRequest) (EmbeddingResponse, error) {
		calls++
		return EmbeddingResponse{}, errors.New("offline")
	})
	c, _ := NewCachedEmbedding(f, "id", true, 1, time.Second, nil)
	for i := 0; i < 2; i++ {
		_, _ = c.Embed(context.Background(), EmbeddingRequest{Input: []string{"a"}})
	}
	if calls != 2 {
		t.Fatal("failure cached")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, e := c.Embed(ctx, EmbeddingRequest{})
	if !errors.Is(e, context.Canceled) || calls != 2 {
		t.Fatal("cancel invoked upstream")
	}
	if _, e = NewCachedEmbedding(f, "id", true, 0, time.Second, nil); e == nil {
		t.Fatal("invalid capacity accepted")
	}
}
