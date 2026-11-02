package com.personalos.common.web;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HTTP 模型请求和脱离 HTTP 的后台执行共享同一个并发上限。 */
@Component
public class ModelConcurrencyGate {
    private final Semaphore permits;
    private final int limit;
    private DatabaseCoordination coordination;
    private boolean background;
    public ModelConcurrencyGate(@Value("${app.model-max-concurrent-requests:4}") int limit) {
        if (limit < 1) throw new IllegalArgumentException("模型并发上限必须为正数");
        permits = new Semaphore(limit);
        this.limit = limit;
    }
    @org.springframework.beans.factory.annotation.Autowired
    void coordinate(DatabaseCoordination coordination, com.personalos.common.config.RuntimeRole role) {
        this.coordination = coordination;
        background = role.worker();
    }
    public Lease tryAcquire() {
        if (!permits.tryAcquire()) return null;
        DatabaseCoordination.Lease backgroundLease = null;
        try {
            if (background && coordination != null) {
                backgroundLease=coordination.acquire("model-background",Math.max(1,limit-1));
                if (backgroundLease==null) { permits.release(); return null; }
            }
            var shared = coordination == null ? null : coordination.acquire("model-concurrency", limit);
            if (coordination != null && shared == null) {
                if (backgroundLease!=null) backgroundLease.close();
                permits.release(); return null;
            }
            return new Lease(shared,backgroundLease);
        } catch (RuntimeException ex) {
            try { if (backgroundLease!=null) backgroundLease.close(); } finally { permits.release(); }
            throw ex;
        }
    }
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final DatabaseCoordination.Lease shared;
        private final DatabaseCoordination.Lease backgroundLease;
        private Lease(DatabaseCoordination.Lease shared,DatabaseCoordination.Lease backgroundLease) {
            this.shared = shared; this.backgroundLease=backgroundLease;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                try { if (shared != null) shared.close(); }
                finally { try { if (backgroundLease!=null) backgroundLease.close(); } finally { permits.release(); } }
            }
        }
    }
}
