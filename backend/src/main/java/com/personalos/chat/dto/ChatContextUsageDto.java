package com.personalos.chat.dto;

/**
 * 上下文读数。budgetTokens 是本轮输入预算，contextWindow 是模型窗口。
 * windowSource=fallback 时 contextWindow 只是保护性默认值，不是已确认的模型能力。
 */
public record ChatContextUsageDto(int promptTokens, int budgetTokens, int contextWindow, boolean estimated,
                                 String modelName, String windowSource) {
}
