package api

// BusinessError 只承载明确可向用户公开的业务文案，不包装数据库/协议原因。
// 调用方不能将原始err.Error()作为message；空文案与默认文案有不同语义。
type BusinessError struct {
	code    ErrorCode
	message string
}

func NewBusinessError(code ErrorCode, message string) *BusinessError {
	switch code {
	case ValidationFailed, IdempotencyKeyConflict, ConfirmationNotActive,
		UnsupportedDocument, ResourceNotFound, MethodNotAllowed, NotAcceptable,
		Unauthorized, Forbidden, BusinessConflict, RateLimited, ContextTooLarge,
		AIUpstreamError, EmbeddingDimensionMismatch, AITimeout, AgentExecutionLimit,
		AINotConfigured, InternalError:
		return &BusinessError{code: code, message: message}
	default:
		return &BusinessError{code: InternalError, message: InternalError.Message()}
	}
}

func (problem *BusinessError) Code() ErrorCode { return problem.code }
func (problem *BusinessError) Error() string   { return problem.message }
