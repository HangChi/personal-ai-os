package com.personalos.common.ai.tool;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 工具参数的解析与校验辅助。
 *
 * <h2>为什么集中在这里</h2>
 * 模型给出的时间几乎总是「自然语言折算后的人类写法」（{@code 2026-10-17}、{@code 2026-10-17T10:00:00}），
 * 而不是规范的 ISO 瞬时时间。解析规则必须**在所有工具里完全一致** ——
 * 否则会出现「任务工具接受 2026-10-17，日程工具却报错」这种让模型与用户都困惑的行为差异。
 * 两处各写一份，迟早会漂移。
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * 解析时间参数。
     *
     * <p>先按标准 ISO 瞬时时间解析；失败再尝试「只有日期」的写法，按当日 00:00 UTC 处理。
     * 支持后者是因为模型把「下周一」折算成日期而不带时间的概率很高，
     * 为此直接拒绝会给用户一个莫名其妙的失败。
     *
     * @param raw       原始字符串；空或空白返回 {@code null}（表示「未提供」）
     * @param fieldName 用于错误提示的字段名
     * @throws BusinessException 格式无法识别时（{@code VALIDATION_FAILED}）
     */
    public static Instant parseInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试「仅日期」的写法
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    fieldName + " 不是合法的时间，请使用 ISO-8601（如 2026-10-17T10:00:00Z 或 2026-10-17）：" + raw);
        }
    }

    /**
     * 解析枚举型参数（大小写不敏感）。
     *
     * <p>与 {@link #parseInstant} 同一个理由：三个工具类曾各写一份枚举解析，
     * 于是「允许值清单」与「错误文案」有了三份副本。模型看到的失败提示必须一致，
     * 否则会出现「任务工具接受 high、日程工具只认 HIGH」这种无从解释的差异。
     *
     * @param type          目标枚举类型
     * @param raw           原始字符串
     * @param label         字段名，用于错误提示（如「优先级」）
     * @param allowedValues 允许值清单，用于错误提示（如 {@code "LOW / MEDIUM / HIGH"}）
     * @param required      为 {@code true} 时不允许省略；否则空值返回 {@code null}
     * @return 解析出的枚举常量；{@code raw} 为空且非必填时返回 {@code null}
     * @throws BusinessException 值不在允许范围内时（{@code VALIDATION_FAILED}）
     */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label,
                                                  String allowedValues, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + "只能是 " + allowedValues + "，收到：" + raw);
        }
    }
}
