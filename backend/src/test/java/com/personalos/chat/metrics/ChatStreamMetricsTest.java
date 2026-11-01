package com.personalos.chat.metrics;

import static org.assertj.core.api.Assertions.*;
import com.personalos.chat.entity.TokenUsageSource;
import com.personalos.chat.support.StreamUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ChatStreamMetricsTest {
    @Test void recordsOutcomeLatencyAndSeparatesActualAndEstimatedUsage() {
        var beans = new DefaultListableBeanFactory();
        var registry = new SimpleMeterRegistry();
        beans.registerSingleton("metrics", registry);
        var metrics = new ChatStreamMetrics(beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        metrics.streamOpened();
        metrics.streamOpened();
        metrics.streamClosed();
        assertThat(registry.get("personalos.chat.streams.active").gauge().value()).isEqualTo(1);
        metrics.completed("OpenAiChatModel", "model", "success", 1_000_000_000L);
        metrics.completed(null, "", "timeout", 2_000_000_000L);
        assertThat(registry.get("personalos.chat.duration").tag("outcome", "success").timer().count()).isEqualTo(1);
        metrics.usage(new StreamUsage("model", 10, 5, TokenUsageSource.ACTUAL));
        metrics.usage(new StreamUsage("model", 20, 7, TokenUsageSource.ESTIMATED));
        assertThat(registry.get("personalos.chat.tokens").tags("source", "ACTUAL", "direction", "input").counter().count()).isEqualTo(10);
        metrics.usage(null);
        metrics.usage(new StreamUsage(null, 0, 0, TokenUsageSource.ESTIMATED));
        metrics.streamClosed();
        metrics.streamClosed();
        assertThat(registry.get("personalos.chat.streams.active").gauge().value()).isZero();
    }
    @Test void missingRegistryDoesNotAffectChat() {
        var metrics = new ChatStreamMetrics(new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        metrics.completed(null, null, "error", 0);
        metrics.usage(new StreamUsage(null, 10, 1, TokenUsageSource.ESTIMATED));
    }
}
