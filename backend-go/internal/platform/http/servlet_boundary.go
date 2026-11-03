package httpmiddleware

import (
	"net/http"
	"slices"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

const containerBadRequestHTML = `<!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Request</title><style type="text/css">body {font-family:Tahoma,Arial,sans-serif;} h1, h2, h3, b {color:white;background-color:#525D76;} h1 {font-size:22px;} h2 {font-size:16px;} h3 {font-size:14px;} p {font-size:12px;} a {color:black;} .line {height:1px;background-color:#525D76;border:none;}</style></head><body><h1>HTTP Status 400 – Bad Request</h1></body></html>`

// WriteServletError保留容器/安全参数解析的错误形态，不套业务信封或回显异常。
func WriteServletError(w http.ResponseWriter, r *http.Request, status int) {
	if r.Method == "HEAD" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		return
	}
	reason := http.StatusText(status)
	if status == 413 {
		reason = "Content Too Large"
	}
	_ = api.WriteControllerJSON(w, status, map[string]any{"timestamp": time.Now().UTC().Format(time.RFC3339Nano), "status": status, "error": reason, "path": r.URL.EscapedPath()})
}

// ServletBoundary复现已验收的Tomcat/StrictHttpFirewall默认拒绝，早于角色/会话/请求ID过滤器。
// Go传输层在Handler前拒绝的非法起始行/HTTP头仍由net/http处理，不伪造Servlet全协议实现。
func ServletBoundary(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		escaped := strings.ToLower(r.URL.EscapedPath())
		for _, blocked := range []string{"%2f", "%5c", "%00"} {
			if strings.Contains(escaped, blocked) {
				w.Header().Set("Content-Type", "text/html;charset=utf-8")
				w.WriteHeader(400)
				if r.Method != "HEAD" {
					_, _ = w.Write([]byte(containerBadRequestHTML))
				}
				return
			}
		}
		bad := strings.Contains(r.URL.Path, "//") || strings.ContainsAny(r.URL.Path, ";\\")
		for _, part := range strings.Split(r.URL.Path, "/") {
			if part == "." || part == ".." {
				bad = true
			}
		}
		for _, c := range r.URL.Path {
			if c < 32 || c == 127 {
				bad = true
			}
		}
		for _, blocked := range []string{"%25", "%3b", "%2e"} {
			if strings.Contains(escaped, blocked) {
				bad = true
			}
		}
		status := 0
		if bad || !slices.Contains([]string{"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}, r.Method) {
			status = 400
		} else if r.Method == "TRACE" {
			status = 405
			w.Header().Set("Allow", "HEAD, DELETE, POST, GET, OPTIONS, PUT")
		}
		if status != 0 {
			WriteServletError(w, r, status)
			return
		}
		next.ServeHTTP(w, r)
	})
}
