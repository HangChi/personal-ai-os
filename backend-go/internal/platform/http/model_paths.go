package httpmiddleware

import (
	"errors"
	"net/url"
	"strings"
)

var modelPostPaths = [...]string{
	"/api/v1/chat/sessions/*/stream",
	"/api/v1/chat/uploads",
	"/api/v1/chat/transcribe",
	"/api/v1/knowledge/documents",
	"/api/v1/knowledge/search",
	"/api/v1/knowledge/ask",
	"/api/v1/settings/model/test",
	"/api/v1/settings/model/profiles/test",
	"/api/v1/settings/model/profiles/probe-models",
	"/api/v1/settings/model/profiles/probe-window",
}

// ModelRequestPath 移植 Java ModelRequestPaths：先去挂载前缀，再解码一次。
// requestURI 必须是不含 query 的原始路径；contextPath 为空或容器的真实挂载前缀。
// 它只决定模型成本保护范围，不代表路由/认证/服务器会接受该路径。
func ModelRequestPath(method, requestURI, contextPath string) (bool, error) {
	if !strings.HasPrefix(requestURI, contextPath) {
		return false, errors.New("模型请求路径与挂载前缀不一致")
	}
	path, err := url.PathUnescape(strings.TrimPrefix(requestURI, contextPath))
	if err != nil {
		// 不把原始请求路径放进错误消息；非法转义不能默认为不需要保护。
		return false, errors.New("模型请求路径包含无效转义")
	}
	if strings.EqualFold(method, "POST") {
		for _, pattern := range modelPostPaths {
			if modelPatternMatches(pattern, path) {
				return true, nil
			}
		}
		return false, nil
	}
	return strings.EqualFold(method, "PUT") && modelPatternMatches("/api/v1/knowledge/documents/*", path), nil
}

// 只支持上方固定模式所需的逐段字面量和完整 '*'，不作为通用 Ant 匹配器。
// Spring AntPathMatcher 忽略空段，但区分末尾斜杠；末尾 '*' 有空尾段例外。
func modelPatternMatches(pattern, path string) bool {
	if !strings.HasPrefix(path, "/") {
		return false
	}
	segments := func(value string) []string {
		return strings.FieldsFunc(value, func(char rune) bool { return char == '/' })
	}
	want, got := segments(pattern), segments(path)
	if len(got) > len(want) {
		return false
	}
	for index, segment := range got {
		if want[index] != "*" && want[index] != segment {
			return false
		}
	}
	trailing := strings.HasSuffix(path, "/")
	if len(got) == len(want) {
		return trailing == strings.HasSuffix(pattern, "/")
	}
	return len(got) == len(want)-1 && want[len(want)-1] == "*" && trailing
}
