package app

import (
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const rateAcquireMetric = "personalos.rate.limit.acquire"
const rateErrorMetric = "personalos.rate.limit.errors"

type actuatorMetricSource interface {
	metricSnapshot(string, url.Values) (any, bool, error)
}

type foundationMetricExporter struct {
	*foundationMetrics
	extra http.Handler
}

func (m *foundationMetricExporter) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	m.foundationMetrics.ServeHTTP(w, r)
	m.extra.ServeHTTP(w, r)
}

// SimpleMeterRegistry默认一分钟轮转、三槽窗口；累计COUNT/TOTAL_TIME不随MAX过期。
// 只报告当前Go实际采集的指标，不伪造JVM、Hikari或尚未实现的业务计量。
type metricWindowMax struct {
	last    time.Time
	current int
	buckets [3]float64
}

func (m *metricWindowMax) rotate(now time.Time) {
	if m.last.IsZero() {
		m.last = now
		return
	}
	steps := now.Sub(m.last) / time.Minute
	if steps <= 0 {
		return
	}
	m.last = m.last.Add(steps * time.Minute)
	if steps >= 3 {
		m.buckets = [3]float64{}
		m.current = 0
		return
	}
	for ; steps > 0; steps-- {
		m.buckets[m.current] = 0
		m.current = (m.current + 1) % len(m.buckets)
	}
}

func (m *metricWindowMax) record(now time.Time, value float64) {
	m.rotate(now)
	for i := range m.buckets {
		m.buckets[i] = max(m.buckets[i], value)
	}
}

type actuatorMeasurement struct {
	Statistic string  `json:"statistic"`
	Value     float64 `json:"value"`
}

type actuatorMetricTag struct {
	Tag    string   `json:"tag"`
	Values []string `json:"values"`
}

type actuatorMetric struct {
	Name          string                `json:"name"`
	BaseUnit      string                `json:"baseUnit,omitempty"`
	Measurements  []actuatorMeasurement `json:"measurements"`
	AvailableTags []actuatorMetricTag   `json:"availableTags"`
}

func (m *foundationMetrics) metricSnapshot(suffix string, query url.Values) (any, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if suffix == "" {
		names := []string{}
		if !m.started.IsZero() {
			names = append(names, "go.goroutines", "go.memory.used", "process.uptime")
		}
		if len(m.httpTimers) > 0 {
			names = append(names, httpRequestMetric)
		}
		if m.counts[0]+m.counts[1] > 0 {
			names = append(names, rateAcquireMetric)
		}
		if m.redisErrors > 0 {
			names = append(names, rateErrorMetric)
		}
		return struct {
			Names []string `json:"names"`
		}{names}, true, nil
	}
	name := strings.TrimPrefix(suffix, "/")
	if name == httpRequestMetric {
		return m.httpMetricSnapshotLocked(query)
	}
	if name == "go.goroutines" || name == "go.memory.used" || name == "process.uptime" {
		return m.runtimeMetricLocked(name, query)
	}
	if name != rateAcquireMetric && name != rateErrorMetric {
		return nil, false, nil
	}
	selected := [2]bool{m.counts[0] > 0, m.counts[1] > 0}
	if name == rateErrorMetric {
		selected = [2]bool{false, m.redisErrors > 0}
	}
	filtered := false
	for _, value := range query["tag"] {
		tag, backend, valid := strings.Cut(value, ":")
		if !valid {
			// 原Actuator非法tag由全局兜底转500，保留兼容行为。
			return nil, false, errors.New("指标标签格式无效")
		}
		filtered = true
		selected[0] = selected[0] && tag == "backend" && backend == "postgres"
		selected[1] = selected[1] && tag == "backend" && backend == "redis"
	}
	if !selected[0] && !selected[1] {
		return nil, false, nil
	}
	result := actuatorMetric{Name: name, AvailableTags: []actuatorMetricTag{}}
	if !filtered {
		values := []string{}
		// 与固定Java HashSet实际输出保持顺序；标签基数固定且不接受用户新增。
		if selected[1] {
			values = append(values, "redis")
		}
		if selected[0] {
			values = append(values, "postgres")
		}
		result.AvailableTags = append(result.AvailableTags, actuatorMetricTag{"backend", values})
	}
	if name == rateErrorMetric {
		result.Measurements = []actuatorMeasurement{{"COUNT", float64(m.redisErrors)}}
		return result, true, nil
	}
	now := time.Now()
	if m.clock != nil {
		now = m.clock()
	}
	var count, total, maximum float64
	for i, include := range selected {
		if include {
			count += float64(m.counts[i])
			total += m.seconds[i]
			m.maxima[i].rotate(now)
			maximum = max(maximum, m.maxima[i].buckets[m.maxima[i].current])
		}
	}
	result.BaseUnit = "seconds"
	result.Measurements = []actuatorMeasurement{{"COUNT", count}, {"TOTAL_TIME", total}, {"MAX", maximum}}
	return result, true, nil
}
