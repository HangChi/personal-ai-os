package observability

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestECSLoggingLevelAndRequestID(t *testing.T) {
	var output bytes.Buffer
	logger := NewLogger(&output, config.LoggingProperties{Format: "ecs", Level: slog.LevelInfo})
	logger.Debug("hidden")
	logger.Info("启动")
	h := httpmiddleware.RequestIDMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		logger.InfoContext(r.Context(), "完成", "outcome", "success")
	}))
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set(httpmiddleware.RequestIDHeader, "log-context-42")
	h.ServeHTTP(httptest.NewRecorder(), r)
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 {
		t.Fatal("日志级别无效")
	}
	for i, line := range lines {
		var row map[string]any
		if err := json.Unmarshal([]byte(line), &row); err != nil {
			t.Fatal(err)
		}
		want := "------------"
		if i == 1 {
			want = "log-context-42"
		}
		if row["requestId"] != want || row["@timestamp"] == nil || row["log.level"] != "info" || row["message"] == nil || row["service.runtime"] != "go" || row["process.pid"] == nil {
			t.Fatal("结构化字段丢失", row)
		}
	}
	output.Reset()
	logger.With("requestId", "detached").Warn("后台")
	if strings.Count(output.String(), `"requestId"`) != 1 || !strings.Contains(output.String(), "detached") {
		t.Fatal("后台请求ID被占位符覆盖")
	}
	output.Reset()
	logger.Warn("无来源任务", "requestId", "")
	if !strings.Contains(output.String(), `"requestId":"------------"`) {
		t.Fatal("显式空请求ID未使用占位符")
	}
}
