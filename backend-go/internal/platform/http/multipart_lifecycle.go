package httpmiddleware

import (
	"context"
	"errors"
	"mime"
	"net/http"
	"sync"
)

type multipartRequestKey struct{}
type multipartRequest struct {
	once   sync.Once
	limits MultipartLimits
	upload *MultipartUpload
	err    error
}

// MultipartLifecycle为整条请求链共享一次惰性解析；安全链读取正文token后业务仍访问同一文件。
// 解析推迟到CSRF需要参数或控制器分派，保留认证拒绝早于上传解析的优先级。
func MultipartLifecycle(next http.Handler, limits MultipartLimits) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		state := &multipartRequest{limits: limits}
		defer func() {
			if state.upload != nil {
				_ = state.upload.Close()
			}
		}()
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), multipartRequestKey{}, state)))
	})
}

func IsMultipart(r *http.Request) bool {
	media, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	return media == "multipart/form-data"
}

// RequestMultipart只在已装配的请求生命周期内使用，重复调用返回相同结果（包括失败）。
// 文件只在ServeHTTP期间有效；持久化由业务显式复制，不能交给后台闭包继续读取。
func RequestMultipart(w http.ResponseWriter, r *http.Request) (*MultipartUpload, error) {
	state, ok := r.Context().Value(multipartRequestKey{}).(*multipartRequest)
	if !ok {
		return nil, errors.New("multipart请求生命周期未装配")
	}
	state.once.Do(func() { state.upload, state.err = ParseMultipart(w, r, state.limits) })
	return state.upload, state.err
}
