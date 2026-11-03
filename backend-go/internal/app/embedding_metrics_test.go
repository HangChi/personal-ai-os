package app

import (
	"context"
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/model"
	"strings"
	"testing"
	"time"
)

type metricEmbedding struct{}

func (metricEmbedding) Embed(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1}}}}, nil
}
func TestEmbeddingMetricsTimerHistogramTagsAndCache(t *testing.T) {
	cache, err := model.NewCachedEmbedding(metricEmbedding{}, "id", true, 10, time.Minute, nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err = cache.Embed(context.Background(), model.EmbeddingRequest{Input: []string{"private"}}); err != nil {
			t.Fatal(err)
		}
	}
	m := &foundationMetrics{models: &model.Module{Embedding: cache, EmbeddingName: "openAiEmbeddingModel"}, embeddingHistogram: true}
	result, ok, err := m.embeddingMetric("personalos.embedding.duration", url.Values{})
	if err != nil || !ok {
		t.Fatal(err)
	}
	timer := result.(actuatorMetric)
	if len(timer.Measurements) != 3 || timer.Measurements[0].Value != 1 || timer.Measurements[2].Statistic != "MAX" {
		t.Fatalf("timer=%+v", timer)
	}
	for _, tags := range [][]string{{"model:wrong"}, {"model:openAiEmbeddingModel", "model:wrong"}, {"other:value"}} {
		if _, ok, err = m.embeddingMetric("personalos.embedding.duration", url.Values{"tag": tags}); err != nil || ok {
			t.Fatal("tag filter accepted", tags, err)
		}
	}
	for _, histogram := range []bool{true, false} {
		m.embeddingHistogram = histogram
		w := httptest.NewRecorder()
		m.writeEmbeddingMetrics(w)
		body := w.Body.String()
		if strings.Contains(body, "_bucket{") != histogram || !strings.Contains(body, "_max{") || strings.Contains(body, "private") {
			t.Fatal("histogram/max/redaction", body)
		}
		if histogram && strings.Count(body, "personalos_embedding_duration_seconds_bucket{") != 69 {
			t.Fatal("wrong fixed bucket count")
		}
	}
}
