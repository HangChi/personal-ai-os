package app

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
)

func (m *foundationMetrics) embeddingMetricNames() []string {
	names := []string{}
	if m.models == nil {
		return names
	}
	if e := m.models.Embedding; e != nil {
		s := e.Stats.Snapshot()
		if s.Calls > 0 {
			names = append(names, "personalos.embedding.duration")
		}
		if len(s.Counts) > 0 {
			names = append(names, "personalos.embedding.cache.requests")
		}
	}
	if e := m.models.Shared; e != nil && len(e.Stats.Snapshot().Counts) > 0 {
		names = append(names, "personalos.embedding.redis.errors")
	}
	return names
}
func (m *foundationMetrics) writeEmbeddingMetrics(w http.ResponseWriter) {
	if m.models == nil {
		return
	}
	if e := m.models.Embedding; e != nil {
		s := e.Stats.Snapshot()
		fmt.Fprintln(w, "# TYPE personalos_embedding_duration_seconds summary")
		fmt.Fprintf(w, "personalos_embedding_duration_seconds_count{model=%q} %d\npersonalos_embedding_duration_seconds_sum{model=%q} %g\n", m.models.EmbeddingName, s.Calls, m.models.EmbeddingName, s.Duration.Seconds())
		fmt.Fprintln(w, "# TYPE personalos_embedding_cache_requests_total counter")
		keys := []string{}
		for k := range s.Counts {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			fmt.Fprintf(w, "personalos_embedding_cache_requests_total{model=%q,result=%q} %d\n", m.models.EmbeddingName, k, s.Counts[k])
		}
	}
	if e := m.models.Shared; e != nil {
		s := e.Stats.Snapshot()
		fmt.Fprintln(w, "# TYPE personalos_embedding_redis_errors_total counter")
		for _, op := range []string{"read", "write"} {
			if count := s.Counts[op+"_error"]; count > 0 {
				fmt.Fprintf(w, "personalos_embedding_redis_errors_total{operation=%q} %d\n", op, count)
			}
		}
	}
}
func (m *foundationMetrics) embeddingMetric(name string, q url.Values) (any, bool, error) {
	if m.models == nil {
		return nil, false, nil
	}
	r := actuatorMetric{Name: name, AvailableTags: []actuatorMetricTag{}}
	filter := map[string]string{}
	for _, v := range q["tag"] {
		key, value, ok := strings.Cut(v, ":")
		if !ok {
			return nil, false, errors.New("指标标签格式无效")
		}
		filter[key] = value
	}
	if name == "personalos.embedding.redis.errors" {
		if m.models.Shared == nil {
			return nil, false, nil
		}
		s := m.models.Shared.Stats.Snapshot()
		total := uint64(0)
		ops := []string{}
		for _, op := range []string{"read", "write"} {
			if n := s.Counts[op+"_error"]; n > 0 {
				ops = append(ops, op)
				if len(filter) == 0 || len(filter) == 1 && filter["operation"] == op {
					total += n
				}
			}
		}
		if total == 0 {
			return nil, false, nil
		}
		r.Measurements = []actuatorMeasurement{{"COUNT", float64(total)}}
		if len(filter) == 0 {
			r.AvailableTags = append(r.AvailableTags, actuatorMetricTag{"operation", ops})
		}
		return r, true, nil
	}
	if m.models.Embedding == nil {
		return nil, false, nil
	}
	s := m.models.Embedding.Stats.Snapshot()
	if v, ok := filter["model"]; ok && v != m.models.EmbeddingName {
		return nil, false, nil
	}
	delete(filter, "model")
	if name == "personalos.embedding.duration" {
		if len(filter) > 0 || s.Calls == 0 {
			return nil, false, nil
		}
		r.BaseUnit = "seconds"
		r.Measurements = []actuatorMeasurement{{"COUNT", float64(s.Calls)}, {"TOTAL_TIME", s.Duration.Seconds()}}
	} else if name == "personalos.embedding.cache.requests" {
		results := []string{}
		var total uint64
		for result, n := range s.Counts {
			results = append(results, result)
			if len(filter) == 0 || len(filter) == 1 && filter["result"] == result {
				total += n
			}
		}
		if total == 0 {
			return nil, false, nil
		}
		sort.Strings(results)
		r.Measurements = []actuatorMeasurement{{"COUNT", float64(total)}}
		if len(filter) == 0 {
			r.AvailableTags = append(r.AvailableTags, actuatorMetricTag{"result", results})
		}
	} else {
		return nil, false, nil
	}
	if q.Get("tag") == "" {
		r.AvailableTags = append(r.AvailableTags, actuatorMetricTag{"model", []string{m.models.EmbeddingName}})
	}
	return r, true, nil
}
