package knowledge

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
)

type p8BatchEmbedding func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error)

func (f p8BatchEmbedding) Embed(ctx context.Context, request model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	return f(ctx, request)
}

func TestP8EmbeddingConservativeBatchesPreserveGlobalOrderAndMetadata(t *testing.T) {
	for _, count := range []int{16, 31, 33, 101} {
		t.Run(strconv.Itoa(count), func(t *testing.T) {
			var batches []int
			store := &Store{Reader: Reader{Properties: testProperties(t)}}
			store.Embedding = p8BatchEmbedding(func(_ context.Context, request model.EmbeddingRequest) (model.EmbeddingResponse, error) {
				if len(request.Input) > 16 {
					t.Fatal("real provider compatibility batch exceeded", len(request.Input))
				}
				batches = append(batches, len(request.Input))
				response := model.EmbeddingResponse{}
				for index := len(request.Input) - 1; index >= 0; index-- {
					value, err := strconv.Atoi(strings.TrimPrefix(request.Input[index], "block-"))
					if err != nil {
						t.Fatal(err)
					}
					vector := make([]float32, 1024)
					vector[0] = float32(value + 1)
					response.Results = append(response.Results, model.EmbeddingResult{Index: index, Vector: vector})
				}
				return response, nil
			})
			blocks := make([]string, count)
			for index := range blocks {
				blocks[index] = fmt.Sprintf("block-%d", index)
			}
			chunks, err := store.prepareTextChunks(context.Background(), blocks, "owned-id", "title", "real.md")
			if err != nil || len(chunks) != count || len(batches) != (count+15)/16 {
				t.Fatal(err, len(chunks), batches)
			}
			for index, chunk := range chunks {
				expected := map[string]any{"document_id": "owned-id", "document_title": "title",
					"filename": "real.md", "chunk_index": index}
				if chunk.Content != blocks[index] || chunk.Vector[0] != float32(index+1) ||
					!reflect.DeepEqual(chunk.Metadata, expected) {
					t.Fatal("cross-batch vector index or metadata changed", index)
				}
			}
		})
	}
}

func TestP8EmbeddingLaterBatchFailureReturnsNoPartialPreparationOrRetry(t *testing.T) {
	for _, invalid := range []bool{false, true} {
		t.Run(strconv.FormatBool(invalid), func(t *testing.T) {
			calls := 0
			store := &Store{Reader: Reader{Properties: testProperties(t)}}
			store.Embedding = p8BatchEmbedding(func(_ context.Context, request model.EmbeddingRequest) (model.EmbeddingResponse, error) {
				calls++
				if calls == 2 {
					if invalid {
						return model.EmbeddingResponse{}, nil
					}
					return model.EmbeddingResponse{}, errors.New("upstream failure")
				}
				response := model.EmbeddingResponse{}
				for index := range request.Input {
					vector := make([]float32, 1024)
					vector[0] = 1
					response.Results = append(response.Results, model.EmbeddingResult{Index: index, Vector: vector})
				}
				return response, nil
			})
			chunks, err := store.prepareTextChunks(context.Background(), make([]string, 49), "owned-id", "title", "real.md")
			if err == nil || chunks != nil || calls != 2 {
				t.Fatal("later failure returned partial chunks or retried", len(chunks), err, calls)
			}
		})
	}
}
