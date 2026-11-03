package httpmiddleware

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/modelgate"
)

type ModelConcurrency interface {
	TryAcquire(context.Context) (*modelgate.Lease, error)
}

// ConcurrencyMiddleware 对应 ModelConcurrencyFilter，位于速率限制之后。
// Go SSE 必须在 ServeHTTP 内完成；禁止返回后在后台使用 ResponseWriter。
// 许可保持至处理器退出，包括整段 SSE、取消或 panic，释放不复用已取消请求 context。
func ConcurrencyMiddleware(next http.Handler, gate ModelConcurrency, logger *slog.Logger) http.Handler {
	if next == nil || gate == nil || logger == nil {
		panic("模型并发中间件依赖不能为空")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		guarded, err := ModelRequestPath(r.Method, r.URL.EscapedPath(), "")
		if err != nil {
			writeRateLimitError(w, api.ValidationFailed, logger)
			return
		}
		if !guarded {
			next.ServeHTTP(w, r)
			return
		}
		lease, err := gate.TryAcquire(r.Context())
		if err != nil {
			logger.Error("模型并发获取失败", "requestId", RequestID(r.Context()))
			writeRateLimitError(w, api.InternalError, logger)
			return
		}
		if lease == nil {
			writeRateLimitError(w, api.RateLimited, logger)
			return
		}
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := lease.Release(ctx); err != nil {
				logger.Error("模型并发释放失败", "requestId", RequestID(r.Context()))
			}
		}()
		next.ServeHTTP(w, r)
	})
}
