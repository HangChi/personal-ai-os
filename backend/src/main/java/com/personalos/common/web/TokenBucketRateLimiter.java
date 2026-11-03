package com.personalos.common.web;

import com.personalos.common.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 平滑补充的模型请求令牌桶。Spring 应用实例使用共享数据库状态，
 * 独立构造的实例使用本地时钟与桶状态。
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
    private DatabaseCoordination coordination;

    @org.springframework.beans.factory.annotation.Autowired
    void coordinate(DatabaseCoordination coordination) { this.coordination = coordination; }

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
        if (coordination != null) return coordination.consume("model-rate", properties.capacity(), properties.period());
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
