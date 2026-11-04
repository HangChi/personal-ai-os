package app

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

type reminderMeasurement struct {
	name       string
	value      uint64
	gauge      bool
	tag, label string
}

func (m *foundationMetrics) reminderMetrics() []reminderMeasurement {
	if m.p5 == nil {
		return nil
	}
	var measurements []reminderMeasurement
	counter := func(name string, value uint64, tag, label string) {
		if value > 0 {
			measurements = append(measurements, reminderMeasurement{"personalos.reminders." + name, value, false, tag, label})
		}
	}
	if m.p5.stream != nil {
		stats := m.p5.stream.Stats()
		measurements = append(measurements, reminderMeasurement{name: "personalos.reminders.stream.connections", value: uint64(stats.Connections), gauge: true})
		counter("stream.snapshots", stats.Snapshots, "", "")
		counter("stream.failures", stats.ReadFailures, "stage", "read")
		counter("stream.failures", stats.WriteFailures, "stage", "write")
	}
	if m.p5.broadcast != nil {
		sent, received, retry := m.p5.broadcast.Counts()
		counter("broadcast.sent", sent, "", "")
		counter("broadcast.received", received, "", "")
		counter("broadcast.retry", retry, "", "")
	}
	if m.p5.webhook != nil {
		counts := m.p5.webhook.DeliveryCounts()
		for _, state := range []string{"failed", "pending", "sent"} {
			counter("webhook.deliveries", counts[state], "result", state)
		}
	}
	if m.p6 != nil {
		counts, timers := m.p6.Stats.Snapshot()
		for key, n := range counts {
			name, result, _ := strings.Cut(key, "|")
			measurements = append(measurements, reminderMeasurement{"personalos.knowledge." + name, n, false, "result", result})
		}
		for key, t := range timers {
			name, result, _ := strings.Cut(key, "|")
			measurements = append(measurements, reminderMeasurement{"personalos.knowledge." + name + ".count", t.Count, false, "result", result}, reminderMeasurement{"personalos.knowledge." + name + ".nanoseconds", uint64(t.Total), false, "result", result})
		}
		measurements = append(measurements, reminderMeasurement{name: "personalos.knowledge.active", value: uint64(m.p6.Stats.Active.Load()), gauge: true})
	}
	return measurements
}

func (m *foundationMetrics) writeReminderMetrics(w http.ResponseWriter) {
	seen := map[string]bool{}
	for _, metric := range m.reminderMetrics() {
		name := strings.ReplaceAll(metric.name, ".", "_")
		kind := "gauge"
		if !metric.gauge {
			kind = "counter"
			name += "_total"
		}
		if !seen[name] {
			fmt.Fprintf(w, "# TYPE %s %s\n", name, kind)
			seen[name] = true
		}
		if metric.tag != "" {
			name += fmt.Sprintf("{%s=%q}", metric.tag, metric.label)
		}
		fmt.Fprintf(w, "%s %d\n", name, metric.value)
	}
}

func (m *foundationMetrics) reminderMetric(name string, query url.Values) (any, bool, error) {
	filters := query["tag"]
	for _, filter := range filters {
		if !strings.Contains(filter, ":") {
			return nil, false, errors.New("指标标签格式无效")
		}
	}
	response := actuatorMetric{Name: name, AvailableTags: []actuatorMetricTag{}}
	var total uint64
	var labels []string
	var tag, stat string
	for _, metric := range m.reminderMetrics() {
		if metric.name != name {
			continue
		}
		matches := true
		for _, filter := range filters {
			if metric.tag == "" || filter != metric.tag+":"+metric.label {
				matches = false
				break
			}
		}
		if !matches {
			continue
		}
		stat = "COUNT"
		if metric.gauge {
			stat = "VALUE"
		}
		total += metric.value
		if len(filters) == 0 && metric.tag != "" {
			tag = metric.tag
			labels = append(labels, metric.label)
		}
	}
	if stat == "" {
		return nil, false, nil
	}
	if tag != "" {
		response.AvailableTags = append(response.AvailableTags, actuatorMetricTag{Tag: tag, Values: labels})
	}
	response.Measurements = []actuatorMeasurement{{stat, float64(total)}}
	return response, true, nil
}
