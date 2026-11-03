package app

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"personal-ai-os/backend-go/internal/business/model"
	"sort"
	"strconv"
	"strings"
)

func (m *foundationMetrics) chatMetricNames() []string {
	if m.chat == nil {
		return nil
	}
	names := []string{"personalos.chat.streams.active"}
	seen := map[string]bool{}
	for _, s := range m.chat.Engine.Metrics.Measurements.Snapshot() {
		if !seen[s.Name] {
			names = append(names, s.Name)
			seen[s.Name] = true
		}
	}
	return names
}
func (m *foundationMetrics) writeChatMetrics(w http.ResponseWriter) {
	if m.chat == nil {
		return
	}
	fmt.Fprintln(w, "# TYPE personalos_chat_streams_active gauge")
	fmt.Fprintf(w, "personalos_chat_streams_active %d\n", m.chat.Engine.Metrics.Active.Load())
	seen := map[string]bool{}
	for _, s := range m.chat.Engine.Metrics.Measurements.Snapshot() {
		name := strings.ReplaceAll(s.Name, ".", "_")
		keys := []string{}
		for k := range s.Tags {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		labels := []string{}
		for _, k := range keys {
			labels = append(labels, k+"="+strconv.Quote(s.Tags[k]))
		}
		tag := strings.Join(labels, ",")
		if s.Name == "personalos.chat.tokens" {
			if !seen[name] {
				fmt.Fprintln(w, "# TYPE", name+"_total", "counter")
				seen[name] = true
			}
			fmt.Fprintf(w, "%s_total{%s} %d\n", name, tag, s.Count)
			continue
		}
		name += "_seconds"
		if !seen[name] {
			fmt.Fprintln(w, "# TYPE", name, "histogram")
			seen[name] = true
		}
		fmt.Fprintf(w, "%s_count{%s} %d\n%s_sum{%s} %g\n%s_max{%s} %g\n", name, tag, s.Count, name, tag, s.Seconds, name, tag, s.Maximum)
		for i, b := range model.EmbeddingHistogramBounds() {
			fmt.Fprintf(w, "%s_bucket{%s,le=%q} %d\n", name, tag, strconv.FormatFloat(b, 'g', -1, 64), s.Buckets[i])
		}
		fmt.Fprintf(w, "%s_bucket{%s,le=\"+Inf\"} %d\n", name, tag, s.Count)
	}
}
func (m *foundationMetrics) chatMetric(name string, q url.Values) (any, bool, error) {
	if m.chat == nil {
		return nil, false, nil
	}
	filter := map[string]string{}
	for _, v := range q["tag"] {
		k, val, ok := strings.Cut(v, ":")
		if !ok {
			return nil, false, errors.New("指标标签格式无效")
		}
		if old, exists := filter[k]; exists && old != val {
			return nil, false, nil
		}
		filter[k] = val
	}
	r := actuatorMetric{Name: name, AvailableTags: []actuatorMetricTag{}}
	if name == "personalos.chat.streams.active" {
		if len(filter) > 0 {
			return nil, false, nil
		}
		r.Measurements = []actuatorMeasurement{{"VALUE", float64(m.chat.Engine.Metrics.Active.Load())}}
		return r, true, nil
	}
	var count, total, maximum float64
	found := false
	tags := map[string]map[string]bool{}
	for _, s := range m.chat.Engine.Metrics.Measurements.Snapshot() {
		if s.Name != name {
			continue
		}
		matches := true
		for k, v := range filter {
			if s.Tags[k] != v {
				matches = false
			}
		}
		if !matches {
			continue
		}
		found = true
		count += float64(s.Count)
		total += s.Seconds
		maximum = max(maximum, s.Maximum)
		for k, v := range s.Tags {
			if _, filtered := filter[k]; filtered {
				continue
			}
			if tags[k] == nil {
				tags[k] = map[string]bool{}
			}
			tags[k][v] = true
		}
	}
	if !found {
		return nil, false, nil
	}
	r.Measurements = []actuatorMeasurement{{"COUNT", count}}
	if name != "personalos.chat.tokens" {
		r.BaseUnit = "seconds"
		r.Measurements = append(r.Measurements, actuatorMeasurement{"TOTAL_TIME", total}, actuatorMeasurement{"MAX", maximum})
	}
	keys := []string{}
	for k := range tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		values := []string{}
		for v := range tags[k] {
			values = append(values, v)
		}
		sort.Strings(values)
		r.AvailableTags = append(r.AvailableTags, actuatorMetricTag{k, values})
	}
	return r, true, nil
}
