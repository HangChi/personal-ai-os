package com.personalos.chat.dto;

/**
 * 会话的上下文用量（阶段 22 仪表盘，token 口径）。
 *
 * <p>分母是**用户选择的预算**（不是模型窗口，也不是写死的字符阈值）：
 * 模型窗口只是上限，用户可以在它之内选一个更小的值。
 *
 * @param promptTokens   估算的上下文字符数折算成的 token
 * @param budgetTokens   用户当前选择的预算（token）
 * @param contextWindow  模型支持的上下文窗口（token）
 * @param estimated      true 表示 promptTokens 是估算值（历史部分按窗口条数求和，
 *                       工具调用往返未计入）；false 表示精确
 */
public record ChatContextUsageDto(int promptTokens, int budgetTokens, int contextWindow, boolean estimated) {
}
