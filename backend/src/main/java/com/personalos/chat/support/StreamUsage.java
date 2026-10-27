package com.personalos.chat.support;

import com.personalos.chat.entity.TokenUsageSource;

/**
 * 一轮对话采集到的用量（流结束时确定，不可变）。
 *
 * <p>把「数字」和「这个数字可不可信」绑在一起传，是为了让调用方无法只取数字
 * 而丢掉 {@code source} —— 那样会把估算当真实值写进库，而库里再没有别的字段
 * 能指出这一点。
 *
 * @param model              模型名快照；上游未上报时为 {@code null}
 * @param promptTokens       送入模型的 token 数
 * @param completionTokens   模型生成的 token 数（含思考 token）
 * @param source             数字来源，见 {@link TokenUsageSource}
 */
public record StreamUsage(String model, int promptTokens, int completionTokens, TokenUsageSource source) {

    /** 两者皆为零时没有记录价值（例如本轮在上游返回任何数据前就失败了） */
    public boolean isMeaningful() {
        return promptTokens > 0 || completionTokens > 0;
    }

    /** 按「字符 ÷ 1.6」折算出的估算用量（provider 未上报 usage 时的降级路径） */
    public static StreamUsage estimated(String model, int promptChars, int completionChars) {
        return new StreamUsage(model, toTokens(promptChars), toTokens(completionChars),
                TokenUsageSource.ESTIMATED);
    }

    /**
     * 字符 → token 折算。
     *
     * <p>与 {@code ContextBudgetService.CHARS_PER_TOKEN}（1.6）保持一致：
     * 仪表盘显示的分母与此处的折算必须是同一个数，否则用户会看到
     * 「仪表盘说用了 1K，统计页说用了 3K」这种自相矛盾。
     * 这里不直接引用那个常量是为了让 chat.support 不依赖 common.ai 的预算服务 ——
     * 两者只是碰巧采用同一个默认值，不该因此耦合；一致性由测试保证。
     */
    private static int toTokens(int chars) {
        return Math.max((int) Math.round(chars / 1.6), 0);
    }
}
