package com.personalos.common.web;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HTTP 模型请求和脱离 HTTP 的后台执行共享同一个并发上限。 */
@Component
public class ModelConcurrencyGate {
    private final Semaphore permits;
    public ModelConcurrencyGate(@Value("${app.model-max-concurrent-requests:4}") int limit) {
        if (limit < 1) throw new IllegalArgumentException("模型并发上限必须为正数");
        permits = new Semaphore(limit);
    }
    public Lease tryAcquire() { return permits.tryAcquire() ? new Lease() : null; }
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() { if (closed.compareAndSet(false, true)) permits.release(); }
    }
}
