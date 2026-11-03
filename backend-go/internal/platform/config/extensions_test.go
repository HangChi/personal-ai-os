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
