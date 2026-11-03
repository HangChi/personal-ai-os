package health

import (
	"net/http"

	"personal-ai-os/backend-go/internal/platform/api"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

// Handler 由调用方挂载到精确的 /api/v1/health；不自动修改诊断入口或 Worker 路由。
func (service *Service) Handler() http.Handler {
	return httpmiddleware.HandleErrors(func(w http.ResponseWriter, request *http.Request) error {
		if request.Method == http.MethodOptions {
			w.Header().Set("Allow", "GET,HEAD,OPTIONS")
			w.WriteHeader(http.StatusOK)
			return nil
		}
		if request.Method != http.MethodGet && request.Method != http.MethodHead {
			return httpmiddleware.MethodNotSupported(request.Method, []string{"GET"})
		}
		status := service.CurrentStatus(request.Context())
		// 原MVC在控制器调用之后协商，406也会执行一次探测。
		contentType, acceptable := httpmiddleware.SelectJSONMediaType(request.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !acceptable {
			return httpmiddleware.NotAcceptable()
		}
		if request.Method == http.MethodHead {
			w.Header().Set("Content-Type", contentType)
			w.WriteHeader(http.StatusOK)
			return nil
		}
		return api.WriteControllerMediaJSON(w, http.StatusOK, api.OK(status), contentType)
	}, service.logger)
}
