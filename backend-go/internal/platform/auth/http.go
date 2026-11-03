package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"mime"
	"net/http"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

type HTTPOptions struct {
	CSRFEnabled  bool
	CookieSecure bool
	// 本地容器使用 JSESSIONID，共享会话使用 SESSION；值格式为 Go 自己的随机 ID。
	CookieName string
}

type HTTP struct {
	service *Service
	store   SessionStore
	options HTTPOptions
	logger  *slog.Logger
}

func NewHTTP(service *Service, store SessionStore, options HTTPOptions, logger *slog.Logger) (*HTTP, error) {
	if service == nil || store == nil || logger == nil {
		return nil, errors.New("认证 HTTP 依赖不能为空")
	}
	if options.CookieName == "" {
		options.CookieName = "JSESSIONID"
	}
	if options.CookieName != "JSESSIONID" && options.CookieName != "SESSION" {
		return nil, errors.New("不支持的会话 Cookie 名称")
	}
	return &HTTP{service: service, store: store, options: options, logger: logger}, nil
}

type principalKey struct{}

// CurrentUsername 只读取本次请求的身份，不使用全局/可变安全上下文。
func CurrentUsername(ctx context.Context) (string, bool) {
	value, ok := ctx.Value(principalKey{}).(string)
	return value, ok && value != ""
}

type requestSession struct {
	id    string
	state Session
}

var errAmbiguousCookie = errors.New("重复会话 Cookie")

// Handler 按 CSRF → 认证 → 控制器/业务的顺序执行，保持原安全链的错误优先级。
// 只组合已有实现；应用尚未解除启动守卫，不能作为业务迁移完成的标志。
func (layer *HTTP) Handler(next http.Handler) http.Handler {
	if next == nil {
		panic("认证下游处理器不能为空")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		if unsafePath(r) {
			layer.writeError(w, api.ValidationFailed, "")
			return
		}
		session, err := layer.load(r)
		if err != nil {
			if errors.Is(err, errAmbiguousCookie) {
				layer.writeError(w, api.ValidationFailed, "")
				return
			}
			layer.storageError(w)
			return
		}
		if layer.options.CSRFEnabled && !safeMethod(r.Method) && !mcpPath(r.URL.Path) {
			if err := layer.ensureCSRF(w, r, &session); err != nil {
				layer.storageError(w)
				return
			}
			presented := csrfInput(w, r)
			if r.MultipartForm != nil {
				defer r.MultipartForm.RemoveAll()
			}
			if subtle.ConstantTimeCompare([]byte(session.state.CSRFToken), []byte(presented)) != 1 {
				layer.writeError(w, api.Forbidden, "")
				return
			}
		}
		if layer.service.Enabled() && !publicPath(r.URL.Path) && session.state.Username == "" {
			layer.writeError(w, api.Unauthorized, "")
			return
		}
		if layer.service.Enabled() && session.state.Username != "" {
			r = r.WithContext(context.WithValue(r.Context(), principalKey{}, session.state.Username))
		}
		if layer.dispatch(w, r, &session) {
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (layer *HTTP) load(r *http.Request) (requestSession, error) {
	id := ""
	seen := false
	for _, cookie := range r.Cookies() {
		if cookie.Name != layer.options.CookieName {
			continue
		}
		if seen {
			return requestSession{}, errAmbiguousCookie
		}
		seen = true
		id = cookie.Value
	}
	if !validSessionID(id) {
		return requestSession{}, nil
	}
	state, ok, err := layer.store.Load(r.Context(), id)
	if err != nil {
		return requestSession{}, err
	}
	if !ok {
		return requestSession{}, nil
	}
	return requestSession{id: id, state: state}, nil
}

func randomID() (string, error) {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", errors.New("会话随机 ID 生成失败")
	}
	return hex.EncodeToString(bytes[:]), nil
}

func randomCSRF() (string, error) {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", errors.New("CSRF 随机令牌生成失败")
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", bytes[:4], bytes[4:6], bytes[6:8], bytes[8:10], bytes[10:]), nil
}

func (layer *HTTP) ensureCSRF(w http.ResponseWriter, r *http.Request, session *requestSession) error {
	if session.state.CSRFToken != "" {
		return nil
	}
	token, err := randomCSRF()
	if err != nil {
		return err
	}
	if session.id != "" {
		state, ok, err := layer.store.EnsureCSRF(r.Context(), session.id, token)
		if err != nil {
			return err
		}
		if ok {
			session.state = state
			return nil
		}
		// 其他请求刚注销/轮换，只能创建新的匿名会话，不能恢复旧身份。
		*session = requestSession{}
	}
	id, err := randomID()
	if err != nil {
		return err
	}
	state := Session{CSRFToken: token}
	if err := layer.store.Rotate(r.Context(), "", id, state); err != nil {
		return err
	}
	*session = requestSession{id: id, state: state}
	layer.cookie(w, id, false)
	return nil
}

func (layer *HTTP) cookie(w http.ResponseWriter, id string, remove bool) {
	cookie := &http.Cookie{Name: layer.options.CookieName, Value: id, Path: "/", HttpOnly: true,
		Secure: layer.options.CookieSecure, SameSite: http.SameSiteLaxMode}
	if remove {
		cookie.Value = ""
		cookie.MaxAge = -1
		cookie.Expires = time.Unix(1, 0).UTC()
	}
	http.SetCookie(w, cookie)
}

func (layer *HTTP) dispatch(w http.ResponseWriter, r *http.Request, session *requestSession) bool {
	path := r.URL.Path
	var allowed string
	switch path {
	case "/api/v1/auth/me", "/api/v1/auth/csrf":
		allowed = "GET, HEAD, OPTIONS"
	case "/api/v1/auth/login", "/api/v1/auth/logout":
		allowed = "POST, OPTIONS"
	default:
		return false
	}
	if r.Method == http.MethodOptions {
		w.Header().Set("Allow", allowed)
		w.WriteHeader(http.StatusOK)
		return true
	}
	valid := r.Method == http.MethodPost && strings.HasPrefix(allowed, "POST") ||
		(r.Method == http.MethodGet || r.Method == http.MethodHead) && strings.HasPrefix(allowed, "GET")
	if !valid {
		layer.controllerError(w, api.MethodNotAllowed, "请求方法 "+r.Method+" 不被支持")
		return true
	}
	switch path {
	case "/api/v1/auth/me":
		var username *string
		if layer.service.Enabled() && session.state.Username != "" {
			username = &session.state.Username
		}
		layer.success(w, r, currentUser{Username: username, AuthEnabled: layer.service.Enabled()})
	case "/api/v1/auth/csrf":
		var token *string
		if layer.options.CSRFEnabled {
			if err := layer.ensureCSRF(w, r, session); err != nil {
				layer.storageError(w)
				return true
			}
			token = &session.state.CSRFToken
		}
		layer.success(w, r, csrfToken{Token: token, HeaderName: "X-CSRF-TOKEN"})
	case "/api/v1/auth/login":
		layer.login(w, r, session)
	case "/api/v1/auth/logout":
		if session.id != "" {
			if err := layer.store.Delete(r.Context(), session.id); err != nil {
				layer.storageError(w)
				return true
			}
			layer.cookie(w, "", true)
		}
		layer.success(w, r, nil)
	}
	return true
}

type currentUser struct {
	Username    *string `json:"username"`
	AuthEnabled bool    `json:"authEnabled"`
}
type csrfToken struct {
	Token      *string `json:"token"`
	HeaderName string  `json:"headerName"`
}

func (layer *HTTP) login(w http.ResponseWriter, r *http.Request, session *requestSession) {
	var request struct {
		Username *string `json:"username"`
		Password *string `json:"password"`
	}
	// 分离绑定与后续副作用；只有绑定成功才进入凭据校验。
	bound := false
	decode := httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		object, err := httpmiddleware.DecodeJSONObject(w, r, 10<<20)
		if err != nil {
			return err
		}
		if request.Username, err = httpmiddleware.JSONStringField(object, "username"); err != nil {
			return err
		}
		if request.Password, err = httpmiddleware.JSONStringField(object, "password"); err != nil {
			return err
		}
		bound = true
		return nil
	}, layer.logger)
	decode.ServeHTTP(w, r)
	if !bound {
		return
	}
	var invalid []string
	if request.Username == nil || javaStrip(*request.Username) == "" {
		invalid = append(invalid, "username 用户名不能为空")
	}
	if request.Password == nil || javaStrip(*request.Password) == "" {
		invalid = append(invalid, "password 密码不能为空")
	}
	if len(invalid) > 0 {
		layer.controllerError(w, api.ValidationFailed, api.ValidationFailed.Message()+": "+strings.Join(invalid, "; "))
		return
	}
	if !layer.service.Enabled() {
		layer.success(w, r, currentUser{AuthEnabled: false})
		return
	}
	ok, err := layer.service.Verify(request.Username, request.Password)
	if err != nil {
		var locked *LockedError
		if errors.As(err, &locked) {
			layer.controllerError(w, api.RateLimited, locked.Error())
		} else {
			layer.controllerError(w, api.InternalError, "")
		}
		return
	}
	if !ok {
		layer.controllerError(w, api.Unauthorized, "用户名或密码错误")
		return
	}
	id, err := randomID()
	if err != nil {
		layer.storageError(w)
		return
	}
	username := layer.service.Username()
	if err := layer.store.Rotate(r.Context(), session.id, id, Session{Username: username}); err != nil {
		layer.storageError(w)
		return
	}
	layer.cookie(w, id, false)
	layer.success(w, r, currentUser{Username: &username, AuthEnabled: true})
}

func (layer *HTTP) success(w http.ResponseWriter, r *http.Request, data any) {
	contentType, acceptable := httpmiddleware.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
	if !acceptable {
		layer.controllerError(w, api.NotAcceptable, "该接口不提供客户端要求的响应格式（Accept 头），请调整 Accept")
		return
	}
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Type", contentType)
		w.WriteHeader(200)
		return
	}
	if err := api.WriteControllerMediaJSON(w, http.StatusOK, api.OK(data), contentType); err != nil {
		layer.logger.Error("写入认证响应失败")
	}
}

func (layer *HTTP) controllerError(w http.ResponseWriter, code api.ErrorCode, message string) {
	if message == "" {
		message = code.Message()
	}
	if err := api.WriteControllerJSON(w, code.HTTPStatus(), api.FailureMessage(code, message)); err != nil {
		layer.logger.Error("写入认证控制器错误响应失败")
	}
}
func (layer *HTTP) writeError(w http.ResponseWriter, code api.ErrorCode, message string) {
	if message == "" {
		message = code.Message()
	}
	if err := api.WriteJSON(w, code.HTTPStatus(), api.FailureMessage(code, message)); err != nil {
		layer.logger.Error("写入认证错误响应失败")
	}
}
func (layer *HTTP) storageError(w http.ResponseWriter) {
	layer.logger.Error("会话存储或随机令牌操作失败")
	layer.writeError(w, api.InternalError, "")
}

func safeMethod(method string) bool {
	return method == "GET" || method == "HEAD" || method == "OPTIONS" || method == "TRACE"
}
func mcpPath(path string) bool { return path == "/mcp" || strings.HasPrefix(path, "/mcp/") }
func publicPath(path string) bool {
	switch path {
	case "/api/v1/health", "/actuator/health", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/me", "/api/v1/auth/csrf", "/error":
		return true
	}
	return strings.HasPrefix(path, "/actuator/health/")
}

// 保守拒绝 Servlet StrictHttpFirewall 禁止的歧义路径，避免白名单/CSRF
// 在 Go 解码路径和其他代理的规范化路径之间产生绕过。完整错误格式差分待 T106。
func unsafePath(r *http.Request) bool {
	path := r.URL.Path
	if strings.Contains(path, "//") || strings.ContainsAny(path, ";\\") {
		return true
	}
	for _, part := range strings.Split(path, "/") {
		if part == "." || part == ".." {
			return true
		}
	}
	for _, char := range path {
		if char < 32 || char == 127 {
			return true
		}
	}
	escaped := strings.ToLower(r.URL.EscapedPath())
	for _, value := range []string{"%2f", "%5c", "%25", "%3b", "%00"} {
		if strings.Contains(escaped, value) {
			return true
		}
	}
	return false
}

func csrfInput(w http.ResponseWriter, r *http.Request) string {
	if token := r.Header.Get("X-CSRF-TOKEN"); token != "" {
		return token
	}
	media, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if media == "application/x-www-form-urlencoded" || media == "multipart/form-data" {
		r.Body = http.MaxBytesReader(w, r.Body, 10<<20)
		if media == "multipart/form-data" {
			if err := r.ParseMultipartForm(1 << 20); err != nil {
				return ""
			}
		} else if err := r.ParseForm(); err != nil {
			return ""
		}
		if token := r.PostForm.Get("_csrf"); token != "" {
			return token
		}
	}
	return r.URL.Query().Get("_csrf")
}
