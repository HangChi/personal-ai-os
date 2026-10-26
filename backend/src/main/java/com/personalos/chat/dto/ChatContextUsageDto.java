package com.personalos.chat.dto;

/**
 * 会话的上下文用量（阶段 22 仪表盘）。
 *
 * <p>刷新页面后前端拿不到上一轮的 done 事件，仪表盘会消失；这个接口让它
 * 在选中会话时就能显示。口径与 done 事件一致：系统提示 + 窗口内历史 + 最近一条用户消息，
 * 其中历史部分是**估算**（见 ChatCompletionService.estimateHistoryChars）。
 *
 * @param promptChars   估算的上下文字符数
 * @param contextBudget 字符预算（app.ai.memory.max-context-chars）
 */
public record ChatContextUsageDto(int promptChars, int contextBudget) {
}
