package app

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

const actuatorMediaType = "application/vnd.spring-boot.actuator.v3+json"

type actuatorComponent struct {
	Status  string         `json:"status"`
	Details map[string]any `json:"details,omitempty"`
}

type actuatorHealth struct {
	Status     string                       `json:"status"`
	Groups     []string                     `json:"groups,omitempty"`
	Components map[string]actuatorComponent `json:"components,omitempty"`
}

func defaultActuatorHealth(up bool, properties config.ActuatorProperties) actuatorHealth {
	state := "UP"
	if !up {
		state = "DOWN"
	}
	result := actuatorHealth{Status: state, Components: map[string]actuatorComponent{"db": {Status: state}}}
	if properties.Probes {
		result.Groups = []string{"liveness", "readiness"}
		result.Components["livenessState"] = actuatorComponent{Status: "UP"}
		result.Components["readinessState"] = actuatorComponent{Status: "UP"}
	}
	if properties.Ping {
		result.Components["ping"] = actuatorComponent{Status: "UP"}
	}
	// Go 监听器不配置 HTTPS 证书链；不能将数据库/Redis 的出站 TLS 假装成入站链。
	if properties.SSL {
		result.Components["ssl"] = actuatorComponent{Status: "UP", Details: map[string]any{"validChains": []string{}, "expiringChains": []string{}, "invalidChains": []string{}}}
	}
	return result
}

func newActuatorHandler(properties config.ActuatorProperties, snapshot func(context.Context) actuatorHealth, metrics http.Handler, logger *slog.Logger) http.Handler {
	return httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		path := r.URL.Path
		healthPath := path == "/actuator/health" || strings.HasPrefix(path, "/actuator/health/")
		metricPath := path == "/actuator/metrics" || strings.HasPrefix(path, "/actuator/metrics/")
		known := healthPath && properties.Health || metricPath && properties.Metrics || path == "/actuator/info" && properties.Info || path == "/actuator/prometheus" && properties.Prometheus && metrics != nil
		if !known {
			return httpmiddleware.NotFound()
		}
		if r.Method == http.MethodOptions {
			w.Header().Set("Allow", "GET,HEAD,OPTIONS")
			w.WriteHeader(http.StatusOK)
			return nil
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return httpmiddleware.MethodNotSupported(r.Method, []string{"GET"})
		}
		if path == "/actuator/prometheus" {
			metrics.ServeHTTP(w, r)
			return nil
		}
		if accept := r.Header.Get("Accept"); accept != "" && strings.Trim(accept, ", \t") == "" {
			// 原显式produces的Actuator对仅逗号Accept走全局500；普通MVC仍使用默认JSON。
			return errors.New("显式端点的Accept候选为空")
		}
		contentType, ok := httpmiddleware.SelectJSONMediaType(r.Header.Get("Accept"), []string{actuatorMediaType, "application/json"})
		if !ok {
			return httpmiddleware.NotAcceptable()
		}
		var value any
		status := http.StatusOK
		if metricPath {
			source, ok := metrics.(actuatorMetricSource)
			if !ok {
				source = &foundationMetrics{}
			}
			var found bool
			var err error
			value, found, err = source.metricSnapshot(strings.TrimPrefix(path, "/actuator/metrics"), r.URL.Query())
			if err != nil {
				return err
			}
			if !found {
				w.WriteHeader(http.StatusNotFound)
				return nil
			}
		} else if healthPath {
			state := snapshot(r.Context())
			_, authenticated := auth.CurrentUsername(r.Context())
			details := properties.HealthDetails == "always" || properties.HealthDetails == "when_authorized" && authenticated
			suffix := strings.TrimPrefix(path, "/actuator/health")
			if suffix != "" {
				name := strings.TrimPrefix(suffix, "/")
				if properties.Probes && (name == "liveness" || name == "readiness") {
					component := name + "State"
					state = actuatorHealth{Status: "UP", Components: map[string]actuatorComponent{component: state.Components[component]}}
				} else if component, ok := state.Components[name]; ok && details {
					if component.Status == "DOWN" {
						status = http.StatusServiceUnavailable
					}
					return writeActuator(w, r, status, contentType, component)
				} else {
					// Actuator 隐藏分量路径时为空404，不套业务异常信封。
					w.WriteHeader(http.StatusNotFound)
					return nil
				}
			}
			if !details {
				state.Components = nil
			}
			if state.Status == "DOWN" {
				status = http.StatusServiceUnavailable
			}
			value = state
		} else {
			info := map[string]any{}
			if properties.InfoEnvironment {
				info["app"] = map[string]string{"name": properties.InfoName, "version": properties.InfoVersion}
			}
			value = info
		}
		return writeActuator(w, r, status, contentType, value)
	}, logger)
}

func writeActuator(w http.ResponseWriter, r *http.Request, status int, contentType string, value any) error {
	data, err := api.MarshalJSONMediaType(value, contentType)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	if r.Method == http.MethodHead {
		return nil
	}
	_, err = w.Write(data)
	return err
}
