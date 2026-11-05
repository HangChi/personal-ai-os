package mcp

const IdempotencyMetaKey = "personal-ai-os/idempotency-key"

// UnknownOutcome never infers rollback from an error returned by a remote tool.
func UnknownOutcome(tool string) string {
	return "MCP 工具执行结果未确认：" + tool + "。外部系统可能已发生变更，请先核对目标状态，不要直接重试或重新发起。"
}
