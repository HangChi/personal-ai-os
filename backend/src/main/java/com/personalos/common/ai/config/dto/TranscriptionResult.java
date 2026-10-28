package com.personalos.common.ai.config.dto;

/**
 * 语音转写的结果。
 *
 * <h2>为什么用「结果对象」而不是抛异常</h2>
 * 与 {@link ModelListResult} 同一口径：这里的多数「失败」其实是**正常状态**。
 * 「没配语音模型」不是错误 —— 前端据此回落到浏览器自带的识别能力；
 * 「用户没说话」也不是错误 —— 提示「没听清」比弹一个红色报错更合适。
 * 若这些都抛异常，前端就只能靠错误文案做字符串匹配，那是必然走偏的做法。
 *
 * <h2>三个状态字段各自回答一个问题</h2>
 * <ul>
 *   <li>{@code ok} —— 拿到文本了吗；</li>
 *   <li>{@code unavailable} —— 是「没配语音模型」这种可以回退的情况吗；</li>
 *   <li>{@code error} —— 需要用户处理的失败原因（null 表示没有失败）。</li>
 * </ul>
 *
 * @param ok          是否成功拿到文本
 * @param text        转写文本；失败或没听清时为空串
 * @param latencyMs   上游耗时（毫秒）；不可用时为 0
 * @param error       失败原因；成功或不可用时为 null
 * @param unavailable 是否因「没配语音模型」而无法转写。为 true 时前端应
 *                    回落到浏览器自带识别，而不是提示错误
 */
public record TranscriptionResult(boolean ok,
                                  String text,
                                  long latencyMs,
                                  String error,
                                  boolean unavailable) {

    /** 成功拿到文本 */
    public static TranscriptionResult success(String text, long latencyMs) {
        return new TranscriptionResult(true, text, latencyMs, null, false);
    }

    /**
     * 没配语音模型 —— **不是错误**，前端应回落。
     *
     * @param reason 原因，用于让前端解释「为什么没走你自己的模型」
     */
    public static TranscriptionResult unavailable(String reason) {
        return new TranscriptionResult(false, "", 0, reason, true);
    }

    /**
     * 识别成功但没听到内容。
     *
     * <p>与 {@link #failed} 分开：用户按了停止但一句话没说时，
     * 「没听清，请再说一次」是准确的提示，而「转写失败」会让人以为功能坏了、
     * 去改配置。
     */
    public static TranscriptionResult empty() {
        return new TranscriptionResult(true, "", 0, null, false);
    }

    /** 真失败：需要用户处理 */
    public static TranscriptionResult failed(String reason) {
        return new TranscriptionResult(false, "", 0, reason, false);
    }
}
