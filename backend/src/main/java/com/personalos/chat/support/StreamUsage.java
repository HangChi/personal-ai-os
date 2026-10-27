package com.personalos.chat.support;

import com.personalos.chat.entity.TokenUsageSource;
import com.personalos.common.ai.TokenEstimation;

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

    /**
     * 按内容构成折算出的估算用量（provider 未上报 usage 时的降级路径）。
     *
     * <h2>为什么这里按语言区分，而预算阈值不区分</h2>
     * 这是「这一轮实际烧了多少 token」的估算，中英文差 3.5 倍（实测：中文
     * 1 token ≈ 1.68 字，英文 1 token ≈ 5.70 字符），用同一个系数必然错一头。
     * 而预算阈值是用户可见的分母，必须稳定 —— 两者诉求不同，故口径不同。
     *
     * @param model           模型名；未知时传 {@code null}
     * @param promptText      送入模型的提示原文，用于判断折算口径；为 {@code null} 时按中文口径
     * @param promptChars     提示的字符数
     * @param completionText  模型生成的正文，用于判断折算口径
     * @param completionChars 正文的字符数
     */
    public static StreamUsage estimated(String model, String promptText, int promptChars,
                                        String completionText, int completionChars) {
        return new StreamUsage(model,
                TokenEstimation.toTokens(promptText, promptChars),
                TokenEstimation.toTokens(completionText, completionChars),
                TokenUsageSource.ESTIMATED);
    }
}
