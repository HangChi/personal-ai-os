package memory

import (
	"context"
	"errors"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
)

type embeddingFunc func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error)

func (f embeddingFunc) Embed(ctx context.Context, req model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	return f(ctx, req)
}

func TestMemoryEmbeddingTimeoutAndInvalidVectors(t *testing.T) {
	store := &Store{}
	ctx := context.Background()
	if got := store.vector(ctx, "some text", time.Second); got != "" {
		t.Fatal("unconfigured model produced a vector")
	}
	store.UseEmbedding(embeddingFunc(func(ctx context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		<-ctx.Done()
		return model.EmbeddingResponse{}, ctx.Err()
	}), "test", 0.92)
	start := time.Now()
	if got := store.vector(ctx, "some text", 20*time.Millisecond); got != "" || time.Since(start) > time.Second {
		t.Fatal("query embedding did not degrade at its deadline")
	}
	for _, size := range []int{0, 100, 1024} {
		store.UseEmbedding(embeddingFunc(func(_ context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
			return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Vector: make([]float32, size)}}}, nil
		}), "test", 0.92)
		if got := store.vector(ctx, "some text", time.Second); got != "" {
			t.Fatalf("invalid or zero vector with dimension %d was accepted", size)
		}
	}
	store.UseEmbedding(embeddingFunc(func(_ context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		t.Error("blank input reached embedding model")
		return model.EmbeddingResponse{}, errors.New("unexpected call")
	}), "test", 0.92)
	if got := store.vector(ctx, "  ", time.Second); got != "" {
		t.Fatal("blank input produced a vector")
	}
}
