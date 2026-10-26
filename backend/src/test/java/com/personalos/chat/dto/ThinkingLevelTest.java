package com.personalos.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ThinkingLevel} 的单元测试。
 *
 * <p>重点是**容错方向**：未知值必须落到 OFF（最省、最快），
 * 而不是抛异常或猜一个档位 —— 请求里的枚举值可能来自旧版本前端或被伪造。
 */
class ThinkingLevelTest {

    @Test
    @DisplayName("合法编码原样解析（首尾空白容忍）")
    void parsesKnownCodes() {
        assertThat(ThinkingLevel.fromCode("off")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("standard")).isEqualTo(ThinkingLevel.STANDARD);
        assertThat(ThinkingLevel.fromCode("deep")).isEqualTo(ThinkingLevel.DEEP);
        assertThat(ThinkingLevel.fromCode("  deep ")).isEqualTo(ThinkingLevel.DEEP);
    }

    @Test
    @DisplayName("null / 空 / 未知 / 大小写变体一律回落 OFF")
    void fallsBackToOff() {
        assertThat(ThinkingLevel.fromCode(null)).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("   ")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("smart")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("STANDARD")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.fromCode("Deep")).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    @DisplayName("预算递增：off=0 < standard < deep")
    void budgetsIncrease() {
        assertThat(ThinkingLevel.OFF.budget()).isZero();
        assertThat(ThinkingLevel.STANDARD.budget()).isGreaterThan(ThinkingLevel.OFF.budget());
        assertThat(ThinkingLevel.DEEP.budget()).isGreaterThan(ThinkingLevel.STANDARD.budget());
    }

    @Test
    @DisplayName("enabled()：只有 OFF 为 false")
    void enabledFlags() {
        assertThat(ThinkingLevel.OFF.enabled()).isFalse();
        assertThat(ThinkingLevel.STANDARD.enabled()).isTrue();
        assertThat(ThinkingLevel.DEEP.enabled()).isTrue();
    }
}
