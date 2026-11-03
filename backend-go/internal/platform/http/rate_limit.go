package httpmiddleware

import (
	"context"
	"log/slog"
	"net/http"

	"personal-ai-os/backend-go/internal/platform/api"
)

// ModelLimiter 只做单次模型成本准入；共享存储和指标通过构造参数传入。
type ModelLimiter interface {
	TryAcquire(context.Context) (bool, error)
}

// RateLimitMiddleware 对应 Java RateLimitFilter。应放在 RequestIDMiddleware 内层、
// 挂载前缀处理之后；尚未接入 NewHandler，避免将本地桶误作为共享保护上线。
// Go 流处理器在同一次 ServeHTTP 调用内执行，不存在 Servlet ASYNC 二次分派。
func RateLimitMiddleware(next http.Handler, limiter ModelLimiter, logger *slog.Logger) http.Handler {
	if next == nil || limiter == nil || logger == nil {
		panic("模型限流中间件依赖不能为空")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		// EscapedPath 保留原始百分号编码；URL.Path 已被 Go 解码，不能再解码一次。
		// 不使用 RequestURI：它带 query，与 Servlet getRequestURI 的语义不同。
		path := request.URL.EscapedPath()
		guarded, err := ModelRequestPath(request.Method, path, "")
		if err != nil {
			writeRateLimitError(w, api.ValidationFailed, logger)
			return
		}
		if !guarded {
			next.ServeHTTP(w, request)
			return
		}
		allowed, err := limiter.TryAcquire(request.Context())
		if err != nil {
			// PostgreSQL 异常由共享策略返回，不伪装成额度耗尽，也不暴露底层凭据。
			// 这里是 Go 错误边界；Servlet 容器级错误差分仍待 T106 验证。
			logger.Error("模型限流后端调用失败", "requestId", RequestID(request.Context()))
			writeRateLimitError(w, api.InternalError, logger)
			return
		}
		if allowed {
			next.ServeHTTP(w, request)
			return
		}
		logger.Warn("模型调用被限流", "method", request.Method, "path", path,
			"requestId", RequestID(request.Context()))
		writeRateLimitError(w, api.RateLimited, logger)
	})
}

func writeRateLimitError(w http.ResponseWriter, code api.ErrorCode, logger *slog.Logger) {
	if err := api.WriteError(w, code); err != nil {
		logger.Error("写入模型限流错误响应失败")
	}
}
