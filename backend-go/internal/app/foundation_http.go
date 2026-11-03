package app

import (
	"log/slog"
	"net/http"
	"strings"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/auth"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/health"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func newFoundationHandler(role config.Role, security *auth.HTTP, limiter httpmiddleware.ModelLimiter, gate httpmiddleware.ModelConcurrency, probe *health.Service, business, actuator http.Handler, logger *slog.Logger) http.Handler {
	healthHandler := probe.Handler()
	router := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/health":
			healthHandler.ServeHTTP(w, r)
		case "/livez", "/readyz":
			if r.Method != http.MethodGet && r.Method != http.MethodHead {
				w.Header().Set("Allow", "GET, HEAD")
				writeError(w, api.MethodNotAllowed, logger)
				return
			}
			status := http.StatusOK
			if r.URL.Path == "/readyz" {
				status = http.StatusServiceUnavailable
			}
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Type", "application/json;charset=UTF-8")
			if r.Method == http.MethodHead {
				w.WriteHeader(status)
				return
			}
			if err := api.WriteJSON(w, status, Diagnostic{Process: "UP", Phase: "foundation", Ready: false, Role: role}); err != nil {
				logger.Error("写入基础诊断响应失败")
			}
		default:
			if r.URL.Path == "/actuator" || strings.HasPrefix(r.URL.Path, "/actuator/") {
				if actuator != nil {
					actuator.ServeHTTP(w, r)
					return
				}
				httpmiddleware.HandleErrors(func(http.ResponseWriter, *http.Request) error { return httpmiddleware.NotFound() }, logger).ServeHTTP(w, r)
				return
			}
			if business == nil {
				httpmiddleware.HandleErrors(func(http.ResponseWriter, *http.Request) error { return httpmiddleware.NotFound() }, logger).ServeHTTP(w, r)
				return
			}
			business.ServeHTTP(w, r)
		}
	})
	var chain http.Handler = security.Handler(router)
	chain = httpmiddleware.ConcurrencyMiddleware(chain, gate, logger)
	chain = httpmiddleware.RateLimitMiddleware(chain, limiter, logger)
	protected := chain
	chain = httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error { protected.ServeHTTP(w, r); return nil }, logger)
	chain = httpmiddleware.RequestIDMiddleware(chain)
	return httpmiddleware.ServletBoundary(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 与Java WorkerIsolationFilter一致，业务请求在会话/限流/数据库访问前返回。
		if role.Worker() && !strings.HasPrefix(r.URL.Path, "/actuator/") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		chain.ServeHTTP(w, r)
	}))
}
