// Package app 组合应用入口。当前仅提供迁移诊断，业务 API 仍未迁移。
package app

import (
	"log/slog"
	"net/http"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

// Diagnostic 的 ready 始终为 false；进程能启动不代表可以接替 Java。
type Diagnostic struct {
	Process string      `json:"process"`
	Phase   string      `json:"phase"`
	Ready   bool        `json:"ready"`
	Role    config.Role `json:"role"`
}

func NewHandler(role config.Role, logger *slog.Logger) http.Handler {
	mux := http.NewServeMux()
	diagnostic := func(status int) http.HandlerFunc {
		return func(w http.ResponseWriter, request *http.Request) {
			if request.Method != http.MethodGet && request.Method != http.MethodHead {
				w.Header().Set("Allow", "GET, HEAD")
				writeError(w, api.MethodNotAllowed, logger)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			if request.Method == http.MethodHead {
				w.Header().Set("Content-Type", "application/json;charset=UTF-8")
				w.WriteHeader(status)
				return
			}
			if err := api.WriteJSON(w, status, Diagnostic{Process: "UP", Phase: "bootstrap", Ready: false, Role: role}); err != nil {
				logger.Error("写入诊断响应失败", "requestId", httpmiddleware.RequestID(request.Context()))
			}
		}
	}
	mux.HandleFunc("/livez", diagnostic(http.StatusOK))
	mux.HandleFunc("/readyz", diagnostic(http.StatusServiceUnavailable))
	mux.HandleFunc("/", func(w http.ResponseWriter, request *http.Request) {
		// Worker 原业务端口的空 404 行为。诊断端点属于本阶段新增的本地能力。
		if role.Worker() {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		writeError(w, api.ResourceNotFound, logger)
	})
	return httpmiddleware.RequestIDMiddleware(mux)
}

func writeError(w http.ResponseWriter, code api.ErrorCode, logger *slog.Logger) {
	if err := api.WriteError(w, code); err != nil {
		logger.Error("写入错误响应失败")
	}
}
