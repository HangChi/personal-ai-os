package chat

import (
	"sort"
	"strings"
	"sync"
	"time"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
)

// 序列只使用已选择的 provider/model；不包含会话、参数、端点或凭据。
const metricSeriesLimit = 256

type MetricSample struct {
	Name             string
	Tags             map[string]string
	Count            uint64
	Seconds, Maximum float64
	Buckets          []uint64
}
type metricKey struct{ name, provider, model, outcome, source, direction string }
type metricValue struct {
	count   uint64
	seconds float64
	buckets []uint64
	maxima  [3]float64
	minute  int64
}
type StreamMeasurements struct {
	mu     sync.Mutex
	values map[metricKey]*metricValue
}

func metricLabel(v string) string {
	if strings.TrimSpace(v) == "" {
		return "unknown"
	}
	if chatcontext.JavaLength(v) > 120 {
		return chatcontext.JavaSubstring(v, 0, 120)
	}
	return v
}
func (v *metricValue) rotate(now time.Time) {
	m := now.Unix() / 60
	if v.minute != 0 && m > v.minute {
		for i := v.minute + 1; i <= m && i <= v.minute+3; i++ {
			v.maxima[i%3] = 0
		}
		if m-v.minute >= 3 {
			v.maxima = [3]float64{}
		}
	}
	v.minute = m
}
func (m *StreamMeasurements) record(k metricKey, d time.Duration, n uint64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.values == nil {
		m.values = map[metricKey]*metricValue{}
	}
	v := m.values[k]
	if v == nil {
		if len(m.values) >= metricSeriesLimit {
			return
		}
		v = &metricValue{buckets: make([]uint64, len(model.EmbeddingHistogramBounds()))}
		m.values[k] = v
	}
	v.count += n
	if k.name == "personalos.chat.tokens" {
		return
	}
	secs := max(0, d.Seconds())
	v.seconds += secs
	now := time.Now()
	v.rotate(now)
	v.maxima[now.Unix()/60%3] = max(v.maxima[now.Unix()/60%3], secs)
	for i, b := range model.EmbeddingHistogramBounds() {
		if secs <= b {
			v.buckets[i] += n
		}
	}
}
func (m *StreamMeasurements) Duration(provider, name, outcome string, d time.Duration) {
	m.record(metricKey{name: "personalos.chat.duration", provider: metricLabel(provider), model: metricLabel(name), outcome: outcome}, d, 1)
}
func (m *StreamMeasurements) First(provider, name string, d time.Duration) {
	m.record(metricKey{name: "personalos.chat.first.content", provider: metricLabel(provider), model: metricLabel(name)}, d, 1)
}
func (m *StreamMeasurements) Usage(v UsageRecord) {
	if v.Prompt == 0 && v.Completion == 0 {
		return
	}
	for direction, n := range map[string]int{"input": v.Prompt, "output": v.Completion} {
		if n >= 0 {
			m.record(metricKey{name: "personalos.chat.tokens", model: metricLabel(v.Model), source: v.Source, direction: direction}, 0, uint64(n))
		}
	}
}
func (m *StreamMeasurements) Snapshot() []MetricSample {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []MetricSample{}
	for k, v := range m.values {
		v.rotate(time.Now())
		tags := map[string]string{"model": k.model}
		if k.provider != "" {
			tags["provider"] = k.provider
		}
		if k.outcome != "" {
			tags["outcome"] = k.outcome
		}
		if k.source != "" {
			tags["source"] = k.source
			tags["direction"] = k.direction
		}
		out = append(out, MetricSample{Name: k.name, Tags: tags, Count: v.count, Seconds: v.seconds, Maximum: max(v.maxima[0], v.maxima[1], v.maxima[2]), Buckets: append([]uint64(nil), v.buckets...)})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		a, b := out[i].Tags, out[j].Tags
		return a["model"]+a["provider"]+a["outcome"]+a["source"]+a["direction"] < b["model"]+b["provider"]+b["outcome"]+b["source"]+b["direction"]
	})
	return out
}
