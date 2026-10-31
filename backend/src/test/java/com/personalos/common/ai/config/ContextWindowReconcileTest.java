package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 显式配置与端点部署限制必须压过内置参考。 */
class ContextWindowReconcileTest {

    @Test
    @DisplayName("端点或手动窗口更小时仍保留，不被较大的内置值覆盖")
    void shouldRespectSmallerConfiguredWindow() {
        assertThat(ModelProfileService.reconcileWindow(129_024, 1_048_576)).isEqualTo(129_024);
    }

    @Test
    @DisplayName("端点报得更大时以端点为准 —— 它更了解自己的部署")
    void shouldPreferLargerProbedWindow() {
        assertThat(ModelProfileService.reconcileWindow(200_000, 131_072)).isEqualTo(200_000);
    }

    @Test
    @DisplayName("只有一边有值时用那一边")
    void shouldUseWhicheverExists() {
        assertThat(ModelProfileService.reconcileWindow(32_768, null)).isEqualTo(32_768);
        assertThat(ModelProfileService.reconcileWindow(null, 1_000_000)).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("都没有 → null（由全局默认兜底，而不是猜一个数）")
    void shouldReturnNullWhenNeitherKnown() {
        assertThat(ModelProfileService.reconcileWindow(null, null)).isNull();
    }

    @Test
    @DisplayName("相等时结果不变（幂等：重复保存不会让值漂移）")
    void shouldBeIdempotent() {
        assertThat(ModelProfileService.reconcileWindow(131_072, 131_072)).isEqualTo(131_072);
    }
}
