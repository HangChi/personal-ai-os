package com.personalos.chat.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 流式对话的运行态指标。
 *
 * <h2>这个指标回答什么问题</h2>
 * 「服务是不是卡住了」。{@code /actuator/metrics} 里的 HTTP 指标只统计**已结束**的请求，
 * 而 SSE 请求恰恰是「不会马上结束」的那一类：模型半天不出第一个 token 时，
 * HTTP 指标一片安静，看上去一切正常。活跃流数持续不降，才是「有流挂住了」的直接证据。
 *
 * <p>用 {@link ObjectProvider} 拿注册表而不是必需依赖：单元测试里常常没有
 * Micrometer 上下文，指标缺失不该让对话功能起不来。
 *
 * <h2>为什么用 Gauge 而不是 Counter</h2>
 * 关心的是**此刻有多少条在跑**，而不是「累计开过多少条」。后者可以由图推导，
 * 前者不行。
 */
@Component
public class ChatStreamMetrics {

    private static final String ACTIVE_STREAMS = "personalos.chat.streams.active";

    private final MeterRegistry registry;

    private final AtomicInteger active = new AtomicInteger();

    public ChatStreamMetrics(ObjectProvider<MeterRegistry> registries) {
        this.registry = registries.getIfAvailable();
        registries.ifAvailable(registry -> Gauge.builder(ACTIVE_STREAMS, active::get)
                .description("当前活跃的流式对话数（含等待首 token 的）")
                .register(registry));
    }

    /** 新流开始 */
    public void streamOpened() {
        active.incrementAndGet();
    }

    /** 流结束（正常/失败/超时/客户端断开都算） */
    public void streamClosed() {
        // 下限钳到 0：多条终止路径可能重复回调，宁可少算也不能变负数
        active.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /** 模型名和提供商来自已选定的配置，不含端点、密钥或会话 id。 */
    public void completed(String provider, String model, String outcome, long nanos) {
        if (registry == null) return;
        io.micrometer.core.instrument.Timer.builder("personalos.chat.duration")
                .tags("provider", label(provider), "model", label(model), "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void usage(com.personalos.chat.support.StreamUsage usage) {
        if (registry == null || usage == null || !usage.isMeaningful()) return;
        registry.counter("personalos.chat.tokens", "model", label(usage.model()),
                "source", usage.source().name(), "direction", "input").increment(usage.promptTokens());
        registry.counter("personalos.chat.tokens", "model", label(usage.model()),
                "source", usage.source().name(), "direction", "output").increment(usage.completionTokens());
    }

    private String label(String value) {
        return value == null || value.isBlank() ? "unknown" : value.substring(0, Math.min(value.length(), 120));
    }
}
