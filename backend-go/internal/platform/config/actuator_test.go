package config

import "testing"

func TestActuatorBaselineAndMonitoring(t *testing.T) {
	for _, monitoring := range []bool{false, true} {
		system := map[string]string{}
		if monitoring {
			system["SPRING_PROFILES_ACTIVE"] = "monitoring"
		}
		p, err := LoadBaselineProperties(PropertyOptions{System: system})
		if err != nil {
			t.Fatal(err)
		}
		a, err := p.Actuator()
		if err != nil || !a.Health || a.Prometheus != monitoring || a.Info == monitoring || a.Metrics == monitoring || a.HealthDetails != "when_authorized" || !a.InfoEnvironment || a.InfoName != "personal-ai-os" || !a.DiskSpace || !a.Probes || !a.SSL || !a.Ping {
			t.Fatalf("Actuator基线配置未接入: %v %v", a, err)
		}
	}
}

func TestActuatorExplicitDetailsAndExposure(t *testing.T) {
	for _, details := range []string{"never", "always", "when_authorized", "WHEN-AUTHORIZED"} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"management.endpoint.health.show-details": details, "management.endpoints.web.exposure.include": "*", "management.endpoints.web.exposure.exclude": "info,prometheus", "management.health.diskspace.enabled": "false"}})
		if err != nil {
			t.Fatal(err)
		}
		a, err := p.Actuator()
		if err != nil || !a.Health || !a.Metrics || a.Info || a.Prometheus || a.DiskSpace {
			t.Fatal("显式配置未生效", err)
		}
	}
	for key, value := range map[string]string{"management.endpoint.health.show-details": "fixture-secret", "management.health.ping.enabled": "maybe"} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{key: value}})
		if err != nil {
			t.Fatal(err)
		}
		if _, err = p.Actuator(); err == nil {
			t.Fatal("非法Actuator配置未拒绝")
		}
	}
}
