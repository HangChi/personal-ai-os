package app

import (
	"context"
	"fmt"
	"net/http/httptest"
	"net/url"
	"personal-ai-os/backend-go/internal/business/model"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestEmbeddingObservationCountsAndTagSelection(t *testing.T) {
	cache, e := model.NewCachedEmbedding(metricEmbedding{}, "fixture", true, 10, time.Minute, nil)
	if e != nil {
		t.Fatal(e)
	}
	for range 3 {
		if _, e = cache.Embed(context.Background(), model.EmbeddingRequest{Input: []string{"sensitive"}}); e != nil {
			t.Fatal(e)
		}
	}
	shared := &model.RedisEmbeddingCache{Stats: model.NewCacheStats()}
	shared.Stats.Counts["read_error"] = 2
	shared.Stats.Counts["write_error"] = 3
	m := &foundationMetrics{models: &model.Module{Embedding: cache, Shared: shared, EmbeddingName: "fixture-model"}}
	wantNames := []string{"personalos.embedding.duration", "personalos.embedding.cache.requests", "personalos.embedding.redis.errors"}
	if !reflect.DeepEqual(m.embeddingMetricNames(), wantNames) {
		t.Fatal(m.embeddingMetricNames())
	}
	for _, tc := range []struct {
		name       string
		tags       []string
		count      float64
		found, bad bool
	}{
		{wantNames[2], nil, 5, true, false}, {wantNames[2], []string{"operation:read"}, 2, true, false}, {wantNames[2], []string{"operation:write"}, 3, true, false},
		{wantNames[2], []string{"operation:missing"}, 0, false, false}, {wantNames[2], []string{"extra:x"}, 0, false, false},
		{wantNames[1], nil, 3, true, false}, {wantNames[1], []string{"model:fixture-model", "result:hit"}, 2, true, false},
		{wantNames[1], []string{"result:miss"}, 1, true, false}, {wantNames[1], []string{"result:missing"}, 0, false, false},
		{wantNames[1], []string{"result:hit", "result:miss"}, 0, false, false}, {wantNames[0], []string{"model:fixture-model"}, 1, true, false},
		{wantNames[0], []string{"broken"}, 0, false, true}, {"unknown", nil, 0, false, false},
	} {
		v, ok, err := m.embeddingMetric(tc.name, url.Values{"tag": tc.tags})
		if (err != nil) != tc.bad || ok != tc.found {
			t.Fatal(tc, v, ok, err)
		}
		if ok {
			r := v.(actuatorMetric)
			if r.Measurements[0].Value != tc.count {
				t.Fatal(tc, r)
			}
			if len(tc.tags) > 0 && len(r.AvailableTags) > 0 {
				t.Fatal("filtered tags leaked", r)
			}
		}
	}
	w := httptest.NewRecorder()
	m.writeEmbeddingMetrics(w)
	body := w.Body.String()
	for _, fragment := range []string{fmt.Sprintf("operation=%q} %d", "read", 2), fmt.Sprintf("operation=%q} %d", "write", 3), fmt.Sprintf("result=%q} %d", "hit", 2), fmt.Sprintf("result=%q} %d", "miss", 1)} {
		if !strings.Contains(body, fragment) {
			t.Fatal(fragment, body)
		}
	}
	if strings.Contains(body, "sensitive") {
		t.Fatal(body)
	}
	for _, empty := range []*foundationMetrics{{}, {models: &model.Module{}}, {models: &model.Module{Shared: &model.RedisEmbeddingCache{Stats: model.NewCacheStats()}}}} {
		if len(empty.embeddingMetricNames()) != 0 {
			t.Fatal("empty series advertised")
		}
		for _, name := range wantNames {
			if _, ok, err := empty.embeddingMetric(name, nil); ok || err != nil {
				t.Fatal(name, ok, err)
			}
		}
		empty.writeEmbeddingMetrics(httptest.NewRecorder())
	}
}
