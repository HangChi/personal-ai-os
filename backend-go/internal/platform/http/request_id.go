// Package httpmiddleware 提供 HTTP 边界能力；业务路由迁移后继续补充。
package httpmiddleware

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"regexp"
	"strings"
)

const RequestIDHeader = "X-Request-Id"

type requestIDKey struct{}

var safeRequestID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

func resolveRequestID(inbound string) string {
	// Java String.trim 只去掉两端 <= U+0020 的字符，不等同于 Go TrimSpace。
	trimmed := strings.TrimFunc(inbound, func(char rune) bool { return char <= 0x20 })
	if safeRequestID.MatchString(trimmed) {
		return trimmed
	}
	var bytes [6]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic("无法生成请求标识")
	}
	return hex.EncodeToString(bytes[:])
}

// RequestID 从请求 context 读取标识；后台任务需显式保存/传递该 context。
func RequestID(ctx context.Context) string {
	value, _ := ctx.Value(requestIDKey{}).(string)
	return value
}

// CopyRequestID 只复制诊断标识；生命周期/取消/身份和值均来自 destination。
// 后台任务不应保留整个 HTTP context 或随客户端断开而被错误取消。
func CopyRequestID(destination, source context.Context) context.Context {
	return context.WithValue(destination, requestIDKey{}, RequestID(source))
}

func RequestIDMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		id := resolveRequestID(request.Header.Get(RequestIDHeader))
		w.Header().Set(RequestIDHeader, id)
		next.ServeHTTP(w, request.WithContext(context.WithValue(request.Context(), requestIDKey{}, id)))
	})
}
