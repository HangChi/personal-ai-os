package app

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/model"
)

var knowledgeGaugeNames = []string{"jobs.pending", "jobs.oldest.ready.seconds", "review.ready", "review.oldest.ready.seconds", "review.failed", "search.pending"}

type knowledgeMetricValue struct {
	name  string
	tags  map[string]string
	value float64
	gauge bool
	timer *knowledge.Measurement
}

func (m *foundationMetrics) knowledgeValues() []knowledgeMetricValue {
	if m.p6 == nil || m.p6.Stats == nil {
		return nil
	}
	counts, timers := m.p6.Stats.Snapshot()
	values := []knowledgeMetricValue{{name: "personalos.knowledge.jobs.active", value: float64(m.p6.Stats.Active.Load()), gauge: true}}
	for key, count := range counts {
		name, label, _ := strings.Cut(key, "|")
		tag := "result"
		switch name {
		case "jobs.attempts":
			tag = "outcome"
		case "rag.results":
			tag = "status"
		case "rag.tokens":
			tag = "kind"
		case "rag.rerank.tokens":
			tag = ""
		}
		tags := map[string]string{}
		if tag != "" && label != "" {
			tags[tag] = label
		}
		values = append(values, knowledgeMetricValue{name: "personalos.knowledge." + name, tags: tags, value: float64(count)})
	}
	for key, timer := range timers {
		name, label, _ := strings.Cut(key, "|")
		tags := map[string]string{}
		switch {
		case strings.HasPrefix(name, "rag.stage."):
			tags["stage"], tags["result"] = strings.TrimPrefix(name, "rag.stage."), label
			name = "rag.stage"
		case strings.HasPrefix(name, "recall."):
			tags["stage"] = strings.TrimPrefix(name, "recall.")
			name = "recall"
		default:
			if label != "" {
				tags["result"] = label
			}
		}
		values = append(values, knowledgeMetricValue{name: "personalos.knowledge." + name, tags: tags, timer: &timer})
	}
	return values
}

func (m *foundationMetrics) knowledgeGauges(parent context.Context) []knowledgeMetricValue {
	if m.p6 == nil || m.p6.Store == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(parent, time.Second)
	defer cancel()
	gauges, err := m.p6.Store.MetricGauges(ctx)
	values := []knowledgeMetricValue{}
	for _, name := range knowledgeGaugeNames {
		value := gauges[name]
		// A failing database gauge is unknown, never a zero backlog.
		if err != nil {
			value = math.NaN()
		}
		values = append(values, knowledgeMetricValue{name: "personalos.knowledge." + name, value: value, gauge: true})
	}
	return values
}

func (m *foundationMetrics) knowledgeMetricNames() []string {
	names := []string{}
	for _, value := range m.knowledgeValues() {
		names = append(names, value.name)
	}
	if m.p6 != nil && m.p6.Store != nil {
		for _, name := range knowledgeGaugeNames {
			names = append(names, "personalos.knowledge."+name)
		}
	}
	slices.Sort(names)
	return slices.Compact(names)
}

func knowledgeLabels(tags map[string]string) string {
	keys := make([]string, 0, len(tags))
	for key := range tags {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	pairs := []string{}
	for _, key := range keys {
		pairs = append(pairs, key+"="+strconv.Quote(tags[key]))
	}
	if len(pairs) == 0 {
		return ""
	}
	return "{" + strings.Join(pairs, ",") + "}"
}

func (m *foundationMetrics) writeKnowledgeMetrics(w http.ResponseWriter, ctx context.Context) {
	values := append(m.knowledgeValues(), m.knowledgeGauges(ctx)...)
	slices.SortFunc(values, func(a, b knowledgeMetricValue) int {
		return strings.Compare(a.name+knowledgeLabels(a.tags), b.name+knowledgeLabels(b.tags))
	})
	seen := map[string]bool{}
	for _, value := range values {
		name := strings.ReplaceAll(value.name, ".", "_")
		kind := "counter"
		if value.gauge {
			kind = "gauge"
		}
		histogram := value.name == "personalos.knowledge.recall" && m.knowledgeRecallHistogram || value.name == "personalos.knowledge.rag.stage" && m.knowledgeRagHistogram
		if value.timer != nil {
			name += "_seconds"
			kind = "summary"
			if histogram {
				kind = "histogram"
			}
		} else if !value.gauge {
			name += "_total"
		}
		if !seen[name] {
			fmt.Fprintln(w, "# TYPE", name, kind)
			if value.timer != nil {
				fmt.Fprintln(w, "# TYPE", name+"_max", "gauge")
			}
			seen[name] = true
		}
		labels := knowledgeLabels(value.tags)
		if value.timer == nil {
			fmt.Fprintln(w, name+labels, value.value)
			continue
		}
		timer := value.timer
		fmt.Fprintln(w, name+"_count"+labels, timer.Count)
		fmt.Fprintln(w, name+"_sum"+labels, timer.Total.Seconds())
		fmt.Fprintln(w, name+"_max"+labels, timer.Max.Seconds())
		if histogram {
			tags := map[string]string{}
			for key, label := range value.tags {
				tags[key] = label
			}
			for i, bound := range model.EmbeddingHistogramBounds() {
				tags["le"] = strconv.FormatFloat(bound, 'g', -1, 64)
				fmt.Fprintln(w, name+"_bucket"+knowledgeLabels(tags), timer.Buckets[i])
			}
			tags["le"] = "+Inf"
			fmt.Fprintln(w, name+"_bucket"+knowledgeLabels(tags), timer.Count)
		}
	}
}

func (m *foundationMetrics) knowledgeMetric(ctx context.Context, name string, query url.Values) (any, bool, error) {
	filters := map[string]string{}
	for _, filter := range query["tag"] {
		key, value, valid := strings.Cut(filter, ":")
		if !valid {
			return nil, false, errors.New("指标标签格式无效")
		}
		if previous, exists := filters[key]; exists && previous != value {
			return nil, false, nil
		}
		filters[key] = value
	}
	values := m.knowledgeValues()
	if slices.Contains(knowledgeGaugeNames, strings.TrimPrefix(name, "personalos.knowledge.")) {
		values = m.knowledgeGauges(ctx)
	}
	response := actuatorMetric{Name: name, AvailableTags: []actuatorMetricTag{}}
	available := map[string][]string{}
	var count, total, maximum float64
	var matched, timer, gauge bool
	for _, value := range values {
		if value.name != name {
			continue
		}
		match := true
		for key, label := range filters {
			if actual, exists := value.tags[key]; !exists || actual != label {
				match = false
				break
			}
		}
		if !match {
			continue
		}
		matched = true
		for key, label := range value.tags {
			if _, filtered := filters[key]; !filtered {
				available[key] = append(available[key], label)
			}
		}
		if value.timer != nil {
			timer = true
			count += float64(value.timer.Count)
			total += value.timer.Total.Seconds()
			maximum = max(maximum, value.timer.Max.Seconds())
		} else {
			count += value.value
			gauge = value.gauge
		}
	}
	if !matched {
		return nil, false, nil
	}
	keys := []string{}
	for key := range available {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	for _, key := range keys {
		labels := available[key]
		slices.Sort(labels)
		response.AvailableTags = append(response.AvailableTags, actuatorMetricTag{key, slices.Compact(labels)})
	}
	if timer {
		response.BaseUnit = "seconds"
		response.Measurements = []actuatorMeasurement{{"COUNT", count}, {"TOTAL_TIME", total}, {"MAX", maximum}}
	} else {
		statistic := "COUNT"
		if gauge {
			statistic = "VALUE"
		}
		response.Measurements = []actuatorMeasurement{{statistic, count}}
	}
	return response, true, nil
}
