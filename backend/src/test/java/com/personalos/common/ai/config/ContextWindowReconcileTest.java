package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ModelProfileService#reconcileWindow} 单元测试。
 *
 * <p>守的是一个具体的线上现象：DashScope 的详情接口对**几乎所有**模型都回
 * {@code max_input_tokens = 129024}（131072 - 2048 的通用实例默认值），
 * 包括文档写着 1M 的 {@code qwen-long} 与 {@code deepseek-v4.1-flash}。
 * 早先的实现「探测值优先」，于是这些模型全被记成 126K —— 用户看到的就是这个数。
 */
class ContextWindowReconcileTest {

    @Test
    @DisplayName("端点报 126K、文档是 1M → 取 1M（探测值是实例默认，不是能力上限）")
    void shouldPreferLargerKnownWindow() {
        assertThat(ModelProfileService.reconcileWindow(129_024, 1_048_576)).isEqualTo(1_048_576);
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
