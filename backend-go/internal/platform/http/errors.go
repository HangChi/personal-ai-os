package httpmiddleware

import (
	"bufio"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"unicode"

	"personal-ai-os/backend-go/internal/platform/api"
)

// Endpoint 在ServeHTTP返回前完成响应；业务错误在提交响应前返回。
// 流建立后应自行发送协议错误；若返回未知错误，边界只中止连接，不追加JSON。
type Endpoint func(http.ResponseWriter, *http.Request) error

// Violation 的Field和Message来自明确的校验规则，不能填入原始提交值或异常消息。
type Violation struct{ Field, Message string }

type requestProblem struct {
	code       api.ErrorCode
	message    string
	allow      string
	disconnect bool
	cause      error
}

func (problem *requestProblem) Error() string { return problem.message }
func (problem *requestProblem) Unwrap() error { return problem.cause }

func InvalidBody(cause error) error {
	return &requestProblem{code: api.ValidationFailed, message: "请求体格式错误，请检查 JSON 结构与字段类型", cause: cause}
}

func MissingParameter(name string) error {
	return &requestProblem{code: api.ValidationFailed, message: api.ValidationFailed.Message() + ": 缺少参数 " + name}
}

func InvalidParameter(name string) error {
	return &requestProblem{code: api.ValidationFailed, message: api.ValidationFailed.Message() + ": 参数 " + name + " 取值非法"}
}

func ValidationFailure(violations []Violation) error {
	details := make([]string, len(violations))
	for i, violation := range violations {
		details[i] = violation.Field + " " + violation.Message
	}
	detail := strings.Join(details, "; ")
	message := api.ValidationFailed.Message()
	// 对齐Java String.isBlank，NBSP/NEL等不能被Go TrimSpace额外吞掉。
	if strings.TrimFunc(detail, javaValidationWhitespace) != "" {
		message += ": " + detail
	}
	return &requestProblem{code: api.ValidationFailed, message: message}
}

func javaValidationWhitespace(r rune) bool {
	return (r >= '\t' && r <= '\r') || (r >= '\u001c' && r <= '\u001f') ||
		(r != '\u00a0' && r != '\u2007' && r != '\u202f' &&
			(unicode.Is(unicode.Zs, r) || r == '\u2028' || r == '\u2029'))
}

func NotFound() error {
	return &requestProblem{code: api.ResourceNotFound, message: api.ResourceNotFound.Message()}
}

func MethodNotSupported(method string, supported []string) error {
	// supported来自路由声明；拷贝到私有字符串并拒绝非法header字符。
	seen := map[string]bool{}
	allowed := make([]string, 0, len(supported))
	for _, value := range supported {
		if validMethodToken(value) && !seen[value] {
			seen[value] = true
			allowed = append(allowed, value)
		}
	}
	return &requestProblem{code: api.MethodNotAllowed, message: "请求方法 " + method + " 不被支持", allow: strings.Join(allowed, ", ")}
}

func validMethodToken(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if !((r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || strings.ContainsRune("!#$%&'*+-.^_`|~", r)) {
			return false
		}
	}
	return true
}

func NotAcceptable() error {
	return &requestProblem{code: api.NotAcceptable, message: "该接口不提供客户端要求的响应格式（Accept 头），请调整 Accept"}
}

func UploadTooLarge(cause error) error {
	return &requestProblem{code: api.UnsupportedDocument, message: "文件过大，请压缩或拆分后重新上传", cause: cause}
}

// IntegrityConflict 由仓储明确分类，边界不会把任意数据库/网络错误猜成409。
func IntegrityConflict(cause error) error {
	return &requestProblem{code: api.BusinessConflict, message: "数据不符合服务端约束（可能超出长度限制或与已有记录冲突），请检查后重试", cause: cause}
}

// ClientDisconnected 必须由实际写入/传输边界明确标记；context取消也可能来自上游。
func ClientDisconnected(cause error) error {
	return &requestProblem{message: "客户端已断开，响应无法继续写入", disconnect: true, cause: cause}
}

func classifyRequestError(err error) *requestProblem {
	// 只认最外层的显式分类。错误的cause可能另含业务异常，不能覆盖外层语义；
	// 多原因Join或循环链没有单一确定分类，统一失败关闭。
	for depth := 0; err != nil && depth < 64; depth++ {
		switch problem := err.(type) {
		case *requestProblem:
			if problem != nil {
				return problem
			}
			return internalRequestProblem()
		case *api.BusinessError:
			if problem != nil {
				return &requestProblem{code: problem.Code(), message: problem.Error()}
			}
			return internalRequestProblem()
		}
		err = errors.Unwrap(err)
	}
	return internalRequestProblem()
}

func internalRequestProblem() *requestProblem {
	return &requestProblem{code: api.InternalError, message: api.InternalError.Message()}
}

// HandleErrors 移植全局异常响应。仅显式组合，不改变诊断入口或现有安全链。
// 日志保留级别/方法/无查询串路径/requestId，但不记录请求体/原始错误/堆栈。
func HandleErrors(endpoint Endpoint, logger *slog.Logger) http.Handler {
	if endpoint == nil || logger == nil {
		panic("异常边界依赖不能为空")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		state := &responseState{ResponseWriter: w}
		var writer http.ResponseWriter = state
		_, flushes := w.(http.Flusher)
		_, hijacks := w.(http.Hijacker)
		switch {
		case flushes && hijacks:
			writer = &flushingHijackingResponse{state}
		case flushes:
			writer = &flushingResponse{state}
		case hijacks:
			writer = &hijackingResponse{state}
		}
		defer func() {
			if recovered := recover(); recovered != nil {
				if recovered == http.ErrAbortHandler {
					panic(http.ErrAbortHandler)
				}
				respondRequestError(state, r, internalRequestProblem(), logger)
			}
		}()
		if err := endpoint(writer, r); err != nil {
			respondRequestError(state, r, classifyRequestError(err), logger)
		}
	})
}

func respondRequestError(w *responseState, r *http.Request, problem *requestProblem, logger *slog.Logger) {
	attributes := []any{"method", r.Method, "uri", r.URL.EscapedPath(), "requestId", RequestID(r.Context())}
	if problem.disconnect {
		logger.Debug("客户端已断开，响应无法继续写入", attributes...)
		return
	}
	if problem.code == api.InternalError {
		logger.Error("未捕获异常，已按内部错误处理", attributes...)
	} else {
		logger.Warn("请求处理失败", append(attributes, "code", problem.code, "supported", problem.allow)...)
	}
	if w.committed {
		panic(http.ErrAbortHandler)
	}
	// 处理器可能已准备下载头但尚未提交，不能让旧长度/压缩破坏JSON错误。
	for _, key := range []string{"Content-Length", "Content-Encoding", "Content-Disposition", "ETag", "Last-Modified"} {
		w.Header().Del(key)
	}
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(problem.code.HTTPStatus())
		return
	}
	if err := api.WriteControllerJSON(w, problem.code.HTTPStatus(), api.FailureMessage(problem.code, problem.message)); err != nil {
		logger.Debug("错误响应写入中断", attributes...)
	}
}

type responseState struct {
	http.ResponseWriter
	committed bool
}

func (w *responseState) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *responseState) WriteHeader(status int) {
	if w.committed {
		return
	}
	w.ResponseWriter.WriteHeader(status)
	if status >= 200 || status == http.StatusSwitchingProtocols {
		w.committed = true
	}
}
func (w *responseState) Write(data []byte) (int, error) {
	w.committed = true
	return w.ResponseWriter.Write(data)
}
func (w *responseState) FlushError() error {
	err := http.NewResponseController(w.ResponseWriter).Flush()
	if !errors.Is(err, http.ErrNotSupported) {
		w.committed = true
	}
	return err
}

// 只在原writer提供Flusher时保留旧式接口；ResponseController总可经Unwrap使用。
type flushingResponse struct{ *responseState }

func (w *flushingResponse) Flush() { _ = w.FlushError() }

func (w *responseState) hijack() (net.Conn, *bufio.ReadWriter, error) {
	conn, buffer, err := http.NewResponseController(w.ResponseWriter).Hijack()
	if err == nil {
		w.committed = true
	}
	return conn, buffer, err
}

type hijackingResponse struct{ *responseState }

func (w *hijackingResponse) Hijack() (net.Conn, *bufio.ReadWriter, error) { return w.hijack() }

type flushingHijackingResponse struct{ *responseState }

func (w *flushingHijackingResponse) Flush() { _ = w.FlushError() }
func (w *flushingHijackingResponse) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	return w.hijack()
}
