// Package api 定义与 Java 后端兼容的普通 JSON 响应契约。
package api

import "net/http"

// ErrorCode 与 Java ErrorCode 的业务码一一对应，不能从码值推断 HTTP 状态。
type ErrorCode int

const (
	Success                    ErrorCode = 0
	ValidationFailed           ErrorCode = 40000
	IdempotencyKeyConflict     ErrorCode = 40001
	ConfirmationNotActive      ErrorCode = 40002
	UnsupportedDocument        ErrorCode = 40003
	ResourceNotFound           ErrorCode = 40004
	MethodNotAllowed           ErrorCode = 40005
	NotAcceptable              ErrorCode = 40600
	Unauthorized               ErrorCode = 40100
	Forbidden                  ErrorCode = 40300
	BusinessConflict           ErrorCode = 40900
	RateLimited                ErrorCode = 42900
	ContextTooLarge            ErrorCode = 41301
	AIUpstreamError            ErrorCode = 50200
	EmbeddingDimensionMismatch ErrorCode = 50201
	AITimeout                  ErrorCode = 50202
	AgentExecutionLimit        ErrorCode = 50203
	AINotConfigured            ErrorCode = 50300
	InternalError              ErrorCode = 50000
)

type errorDescription struct {
	message string
	status  int
}

func (code ErrorCode) description() errorDescription {
	switch code {
	case Success:
		return errorDescription{"OK", http.StatusOK}
	case ValidationFailed:
		return errorDescription{"参数校验失败", http.StatusBadRequest}
	case IdempotencyKeyConflict:
		return errorDescription{"幂等键与请求内容不一致，同一幂等键必须对应相同请求", http.StatusConflict}
	case ConfirmationNotActive:
		return errorDescription{"该确认已失效或已被使用", http.StatusConflict}
	case UnsupportedDocument:
		return errorDescription{"不支持的文档", http.StatusBadRequest}
	case ResourceNotFound:
		return errorDescription{"资源不存在", http.StatusNotFound}
	case MethodNotAllowed:
		return errorDescription{"请求方法不被支持", http.StatusMethodNotAllowed}
	case NotAcceptable:
		return errorDescription{"响应格式不被支持", http.StatusNotAcceptable}
	case Unauthorized:
		return errorDescription{"未登录或登录已过期", http.StatusUnauthorized}
	case Forbidden:
		return errorDescription{"请求被拒绝", http.StatusForbidden}
	case BusinessConflict:
		return errorDescription{"业务规则冲突", http.StatusConflict}
	case RateLimited:
		return errorDescription{"请求过于频繁，请稍后再试", http.StatusTooManyRequests}
	case ContextTooLarge:
		return errorDescription{"上下文超出当前模型可用空间，请减少附件或检索内容、降低思考深度，或切换更大窗口模型", http.StatusBadRequest}
	case AIUpstreamError:
		return errorDescription{"AI 服务调用失败，请稍后重试或检查模型配置", http.StatusBadGateway}
	case EmbeddingDimensionMismatch:
		return errorDescription{"嵌入模型输出维度与向量表结构不一致，请检查嵌入模型配置或新增迁移重建向量列", http.StatusInternalServerError}
	case AITimeout:
		return errorDescription{"模型响应超时，请稍后重试或降低思考深度", http.StatusGatewayTimeout}
	case AgentExecutionLimit:
		return errorDescription{"工具调用已停止：重复失败或执行步数达到上限。已完成的操作不会撤销，请先查看执行记录。", http.StatusBadGateway}
	case AINotConfigured:
		return errorDescription{"AI 模型未配置，请设置 AI_CHAT_PROVIDER 为 ollama 或 openai", http.StatusServiceUnavailable}
	default:
		return errorDescription{"服务器内部错误", http.StatusInternalServerError}
	}
}

// Message 返回默认中文文案；业务错误的自定义文案由调用方负责脱敏。
func (code ErrorCode) Message() string { return code.description().message }

// HTTPStatus 返回实际 HTTP 映射，包括 41301→400 和 50201→500。
func (code ErrorCode) HTTPStatus() int { return code.description().status }
