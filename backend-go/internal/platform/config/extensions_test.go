package config

import (
	"strings"
	"testing"
)

func TestFoundationExtensionBoundary(t *testing.T) {
	for _, profiles := range []string{"", "monitoring", "worker-index", "worker-review", "worker-delivery", "runtime-api"} {
		p, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{"SPRING_PROFILES_ACTIVE": profiles}})
		if err != nil {
			t.Fatal(err)
		}
		if err := p.ValidateFoundationExtensions(); err != nil {
			t.Fatal(profiles, err)
		}
	}
	for _, key := range []string{"SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", "SPRING_DATASOURCE_JNDI_NAME", "SPRING_FLYWAY_TABLE", "SPRING_FLYWAY_SCHEMAS", "SERVER_TOMCAT_MAX_PART_COUNT", "SERVER_SERVLET_CONTEXT_PATH", "SERVER_SSL_ENABLED", "MANAGEMENT_SERVER_ADDRESS", "LOGGING_LEVEL_COM_PERSONALOS", "LOGGING_PATTERN_CONSOLE"} {
		p, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{key: "fixture-private-value"}})
		if err != nil {
			t.Fatal(err)
		}
		err = p.ValidateFoundationExtensions()
		if err == nil || strings.Contains(err.Error(), "fixture-private") {
			t.Fatal("扩展未拒绝或泄露值", key, err)
		}
	}
}

func TestLoggingAndHTTPObservationConfiguration(t *testing.T) {
	p, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{"SPRING_PROFILES_ACTIVE": "monitoring", "LOGGING_STRUCTURED_FORMAT_CONSOLE": "ecs", "LOGGING_LEVEL_ROOT": "DEBUG"}})
	if err != nil {
		t.Fatal(err)
	}
	logging, err := p.Logging()
	if err != nil || logging.Format != "ecs" || logging.Level != -4 {
		t.Fatal("日志配置未绑定", err)
	}
	a, err := p.Actuator()
	if err != nil || !a.HTTPHistogram || !a.HTTPRequests || !a.EmbeddingHistogram || !a.KnowledgeRecallHistogram || !a.KnowledgeRagHistogram {
		t.Fatal("monitoring直方图未绑定", err)
	}
	for key, value := range map[string]string{"logging.structured.format.console": "private-format", "logging.level.root": "private-level", "management.metrics.distribution.percentiles-histogram.http.server.requests": "private-bool"} {
		p, _ := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{key: value}})
		if strings.HasPrefix(key, "logging") {
			_, err = p.Logging()
		} else {
			_, err = p.Actuator()
		}
		if err == nil || strings.Contains(err.Error(), value) {
			t.Fatal("配置未安全拒绝", key, err)
		}
	}
}
