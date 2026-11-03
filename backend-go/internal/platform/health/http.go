package health

import (
	"net/http"

	"personal-ai-os/backend-go/internal/platform/api"
)

// Handler 由调用方挂载到精确的 /api/v1/health；不自动修改诊断入口或 Worker 路由。
func (service *Service) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet && request.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			if err := api.WriteError(w, api.MethodNotAllowed); err != nil {
				service.logger.Error("写入健康检查错误响应失败")
			}
			return
		}
		status := service.CurrentStatus(request.Context())
		if request.Method == http.MethodHead {
			w.Header().Set("Content-Type", "application/json;charset=UTF-8")
			w.WriteHeader(http.StatusOK)
			return
		}
		if err := api.WriteJSON(w, http.StatusOK, api.OK(status)); err != nil {
			service.logger.Error("写入健康状态响应失败")
		}
	})
}
