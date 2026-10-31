package com.personalos.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型调用限流配置（前缀 {@code app.rate-limit}）。
 *
 * <p><b>为什么需要它</b>：{@code /chat/**\/stream}、知识检索、语音转写等入口
 * 每一个都会产生真实的模型调用成本。单用户本地场景下「被人刷」的概率不高，但
 * 「脚本跑飞了」很常见 —— 一个写错的重试循环能在一分钟内烧掉一天的额度。
 * 限流在这里的作用不是防攻击，而是**给失控的客户端一个刹车**。
 *
 * <p>参数校验在紧凑构造器里做（启动期失败，而不是运行期算出负数）：
 * 限流配错时最糟的情况是「什么都不限」，静默失效比启动失败危险得多。
 *
 * @param enabled 是否启用（默认启用；压测或批量导入数据时可临时关闭）
 * @param capacity 桶容量，即一个 {@code period} 内允许的突发请求数
 * @param period   补充一整桶所需的时间
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") int capacity,
        @DefaultValue("1m") Duration period) {

    public RateLimitProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("app.rate-limit.capacity 必须为正数，当前为 " + capacity);
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("app.rate-limit.period 必须为正的时长，当前为 " + period);
        }
    }
}
