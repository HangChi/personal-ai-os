package config

import (
	"errors"
	"strings"
)

type ActuatorProperties struct {
	Health, Info, Metrics, Prometheus             bool
	InfoEnvironment, DiskSpace, SSL, Ping, Probes bool
	HealthDetails                                 string
	InfoName, InfoVersion                         string
	HTTPHistogram, HTTPRequests                   bool
}

func (ActuatorProperties) String() string               { return "ActuatorProperties{redacted}" }
func (p ActuatorProperties) GoString() string           { return p.String() }
func (ActuatorProperties) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

func (p *Properties) Actuator() (ActuatorProperties, error) {
	if p == nil {
		return ActuatorProperties{}, errors.New("Actuator 配置来源不能为空")
	}
	values := map[string]string{}
	for _, key := range []string{"management.endpoints.web.exposure.include", "management.endpoints.web.exposure.exclude", "management.endpoint.health.show-details", "management.info.env.enabled", "info.app.name", "info.app.version", "management.health.diskspace.enabled", "management.health.ssl.enabled", "management.health.ping.enabled", "management.endpoint.health.probes.enabled", "management.metrics.distribution.percentiles-histogram.http.server.requests", "management.metrics.enable.http.server.requests"} {
		value, found, err := p.Get(key)
		if err != nil {
			return ActuatorProperties{}, err
		}
		if found {
			values[key] = value
		}
	}
	result := ActuatorProperties{HealthDetails: "when_authorized", InfoName: values["info.app.name"], InfoVersion: values["info.app.version"]}
	if value, found := values["management.endpoint.health.show-details"]; found {
		result.HealthDetails = strings.ReplaceAll(strings.ToLower(value), "-", "_")
	}
	if result.HealthDetails != "never" && result.HealthDetails != "always" && result.HealthDetails != "when_authorized" {
		return ActuatorProperties{}, &propertyError{"management.endpoint.health.show-details", ErrInvalidProperty}
	}
	for _, item := range []struct {
		key      string
		fallback bool
		target   *bool
	}{
		{"management.info.env.enabled", false, &result.InfoEnvironment},
		{"management.health.diskspace.enabled", true, &result.DiskSpace},
		{"management.health.ssl.enabled", true, &result.SSL},
		{"management.health.ping.enabled", true, &result.Ping},
		{"management.endpoint.health.probes.enabled", true, &result.Probes},
		{"management.metrics.distribution.percentiles-histogram.http.server.requests", false, &result.HTTPHistogram},
		{"management.metrics.enable.http.server.requests", true, &result.HTTPRequests},
	} {
		value, err := bindBoolean(values, item.key, item.fallback)
		if err != nil {
			return ActuatorProperties{}, err
		}
		*item.target = value
	}
	included, excluded := map[string]bool{}, map[string]bool{}
	for _, item := range []struct {
		key    string
		target map[string]bool
	}{{"management.endpoints.web.exposure.include", included}, {"management.endpoints.web.exposure.exclude", excluded}} {
		for _, name := range strings.Split(values[item.key], ",") {
			item.target[strings.TrimSpace(name)] = true
		}
	}
	for name, target := range map[string]*bool{"health": &result.Health, "info": &result.Info, "metrics": &result.Metrics, "prometheus": &result.Prometheus} {
		*target = (included[name] || included["*"]) && !excluded[name] && !excluded["*"]
	}
	return result, nil
}
