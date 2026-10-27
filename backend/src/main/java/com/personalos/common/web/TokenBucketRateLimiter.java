package com.personalos.common.web;

import com.personalos.common.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 令牌桶限流器（进程内、单桶）。
 *
 * <h2>为什么自己写而不是引 Bucket4j</h2>
 * 本项目是**单用户本地应用**：没有多实例、不需要分布式协调、也不追求精确到毫秒的
 * 漏桶语义。这点需求用一个 {@code synchronized} 方法加两个字段就够，
 * 而 Bucket4j 会带来一个需要长期跟进的依赖。
 *
 * <h2>为什么是「桶」而不是「固定窗口计数」</h2>
 * 固定窗口有个众所周知的边界问题：窗口末尾打满、下一窗口开头再打满，
 * 实际速率是配置值的两倍。令牌桶是平滑补充的，没有这个缝。
 *
 * <h2>并发模型</h2>
 * 方法级 {@code synchronized}。单用户下并发数是个位数，锁竞争带来的开销
 * 远小于「用错无锁结构导致限流器本身成为 bug 源」的风险。
 * 把这里换成多用户共享时，必须改成「每用户一个桶」并考虑清理策略。
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final RateLimitProperties properties;

    /** 每个令牌的平均补充间隔（纳秒）。用 double 是为了支持「每分钟 7 个」这类除不尽的配置 */
    private final double nanosPerToken;

    /** 当前可用令牌数，上限为 {@code capacity} */
    private double tokens;

    private long lastRefillNanos;

    public TokenBucketRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
        this.nanosPerToken = (double) properties.period().toNanos() / properties.capacity();
        // 启动即注满：否则应用刚起来的前几个请求会被莫名其妙地拒掉
        this.tokens = properties.capacity();
        this.lastRefillNanos = System.nanoTime();
        log.info("模型调用限流已配置：{}/{}，启用={}",
                properties.capacity(), properties.period(), properties.enabled());
    }

    /**
     * 尝试取一个令牌。
     *
     * @return {@code true} 表示放行，{@code false} 表示应拒绝
     */
    public synchronized boolean tryAcquire() {
        if (!properties.enabled()) {
            return true;
        }
        refill();
        if (tokens < 1.0d) {
            return false;
        }
        tokens -= 1.0d;
        return true;
    }

    /** 按流逝时间补充令牌，上限不超过桶容量 */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            // 时钟回拨（System.nanoTime 理论上单调，但跨 JVM 实现的差异仍按 0 处理）
            return;
        }
        tokens = Math.min(properties.capacity(), tokens + elapsed / nanosPerToken);
        lastRefillNanos = now;
    }

    /** 当前可用令牌数（仅用于自检与测试，不参与限流判断） */
    synchronized double availableTokens() {
        refill();
        return tokens;
    }
}
