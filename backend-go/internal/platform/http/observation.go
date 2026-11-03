package httpmiddleware

import (
	"context"
	"net/http"
	"strings"
	"time"
)

type routeKey struct{}
type observedRoute struct{ pattern string }

// SetRequestRoute只接收服务器路由声明，禁止传入用户路径/查询串/ID。
func SetRequestRoute(ctx context.Context, pattern string) {
	if route, ok := ctx.Value(routeKey{}).(*observedRoute); ok && len(pattern) <= 128 && strings.HasPrefix(pattern, "/") && !strings.ContainsAny(pattern, "?\r\n\x00") {
		route.pattern = pattern
	}
}

type HTTPObservation struct {
	Method, Route string
	Status        int
	Elapsed       time.Duration
	Aborted       bool
}

// ObserveHTTP的完成回调只在ServeHTTP退出时运行，SSE首个Flush不会提前累计。
// 开始回调用于当前活跃请求数；保留Flusher/Hijacker/ResponseController能力。
func ObserveHTTP(next http.Handler, started func(), completed func(HTTPObservation)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		if started != nil {
			started()
		}
		route := &observedRoute{}
		r = r.WithContext(context.WithValue(r.Context(), routeKey{}, route))
		state := &responseState{ResponseWriter: w}
		var writer http.ResponseWriter = state
		_, flush := w.(http.Flusher)
		_, hijack := w.(http.Hijacker)
		switch {
		case flush && hijack:
			writer = &flushingHijackingResponse{state}
		case flush:
			writer = &flushingResponse{state}
		case hijack:
			writer = &hijackingResponse{state}
		}
		finished := false
		defer func() {
			status := state.status
			if status == 0 {
				status = 200
				if !finished {
					status = 500
				}
			}
			if completed != nil {
				completed(HTTPObservation{Method: r.Method, Route: route.pattern, Status: status, Elapsed: time.Since(start), Aborted: !finished})
			}
		}()
		next.ServeHTTP(writer, r)
		finished = true
	})
}
