package app

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/ratelimit"
)

func TestRateMetricWindowRotationAndCumulativeTotals(t *testing.T) {
	now := time.Unix(1000, 0)
	m := &foundationMetrics{clock: func() time.Time { return now }}
	check := func(count, total, maximum float64) {
		t.Helper()
		value, found, err := m.metricSnapshot("/"+rateAcquireMetric, nil)
		if err != nil || !found {
			t.Fatal("实际指标丢失", err)
		}
		metric := value.(actuatorMetric)
		if metric.Measurements[0].Value != count || metric.Measurements[1].Value != total || metric.Measurements[2].Value != maximum {
			t.Fatal("窗口或累计值错误", metric.Measurements)
		}
	}
	m.RecordAcquisition(ratelimit.Postgres, time.Second, false)
	check(1, 1, 1)
	now = now.Add(2 * time.Minute)
	check(1, 1, 1)
	m.RecordAcquisition(ratelimit.Postgres, 500*time.Millisecond, false)
	now = now.Add(time.Minute)
	check(2, 1.5, 0.5)
	now = now.Add(3 * time.Minute)
	check(2, 1.5, 0)
	now = now.Add(-time.Hour)
	check(2, 1.5, 0)
	m.RecordAcquisition(ratelimit.Postgres, -time.Second, false)
	check(2, 1.5, 0)
	if _, found, err := m.metricSnapshot("/"+rateErrorMetric, nil); err != nil || found {
		t.Fatal("不能在尚无Redis错误时伪造counter")
	}
	if _, found, err := m.metricSnapshot("/"+rateAcquireMetric, url.Values{"tag": {"backend:redis"}}); err != nil || found {
		t.Fatal("不能伪造未记录的后端标签")
	}
}

func TestActuatorMetricExposureAndEmptyRegistry(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := &foundationMetrics{}
	for _, enabled := range []bool{false, true} {
		h := newActuatorHandler(config.ActuatorProperties{Metrics: enabled}, nil, m, logger)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", "/actuator/metrics", nil))
		if enabled && (w.Code != 200 || w.Body.String() != `{"names":[]}`) || !enabled && w.Code != 404 {
			t.Fatal("暴露开关/空注册表错误", w.Code, w.Body.String())
		}
	}
	m.RecordAcquisition(ratelimit.Redis, time.Millisecond, true)
	value, _, err := m.metricSnapshot("", nil)
	data, _ := json.Marshal(value)
	if err != nil || !strings.Contains(string(data), rateErrorMetric) {
		t.Fatal("实际采集的counter没有进入名称清单")
	}
}
