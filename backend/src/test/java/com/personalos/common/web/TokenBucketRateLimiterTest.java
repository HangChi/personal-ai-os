package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.config.RateLimitProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 令牌桶限流器单元测试。
 *
 * <p>时间相关的用例用 100ms 级的周期，避免「等一分钟看结果」。补充速率按
 * {@code period / capacity} 计算，所以短周期同样能验证「按速率补充」而非「整点重置」。
 */
class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("桶满时可连取 capacity 次，再多一次被拒")
    void drainsToZero() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
                new RateLimitProperties(true, 3, Duration.ofHours(1)));

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).as("第 4 次应被拒绝").isFalse();
    }

    @Test
    @DisplayName("时间流逝后按速率补充，且不超过容量上限")
    void refillsOverTime() throws InterruptedException {
        // 100ms 补满 2 个 → 每 50ms 一个令牌
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
                new RateLimitProperties(true, 2, Duration.ofMillis(100)));

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        // 睡过一个完整周期：应恰好补回 2 个（而不是无限累积）
        Thread.sleep(120);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).as("补充不应超过容量").isFalse();
    }

    @Test
    @DisplayName("关闭限流时无限放行（压测与批量导入用）")
    void disabledAllowsEverything() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
                new RateLimitProperties(false, 1, Duration.ofHours(1)));

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
    }

    @Test
    @DisplayName("容量或周期非法时构造即失败 —— 静默不限流比启动失败危险得多")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new RateLimitProperties(true, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");

        assertThatThrownBy(() -> new RateLimitProperties(true, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
    }
}
