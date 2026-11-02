package com.personalos.common.web;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务、数据库时钟和带续租的所有权；租约过期后旧执行者不能提交写入。 */
@Component
public class DatabaseCoordination {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Map<UUID, Lease> held = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task,"coordination-renewal"); thread.setDaemon(true); return thread;
    });

    public DatabaseCoordination(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        renewal.scheduleWithFixedDelay(this::renew,10,10,TimeUnit.SECONDS);
    }

    public Lease acquire(String resource, int capacity) {
        if (resource.length() > 160 || capacity < 1 || capacity > 100) throw new IllegalArgumentException("Invalid lease capacity");
        UUID owner = UUID.randomUUID();
        Integer slot = tx.execute(status -> {
            db.update("INSERT INTO runtime_lease(resource,slot) SELECT ?,generate_series(0,?-1) ON CONFLICT DO NOTHING", resource, capacity);
            var slots = db.query("""
                    WITH ready AS (SELECT slot FROM runtime_lease WHERE resource=? AND slot<?
                        AND lease_until<clock_timestamp() ORDER BY slot FOR UPDATE SKIP LOCKED LIMIT 1)
                    UPDATE runtime_lease l SET owner=?,lease_until=clock_timestamp()+interval '120 seconds'
                    FROM ready r WHERE l.resource=? AND l.slot=r.slot RETURNING l.slot
                    """, (rs, n) -> rs.getInt(1), resource, capacity, owner, resource);
            return slots.isEmpty() ? null : slots.getFirst();
        });
        if (slot == null) return null;
        Lease lease = new Lease(resource, slot, owner);
        held.put(owner, lease);
        return lease;
    }

    public boolean consume(String name, int capacity, Duration period) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            db.update("INSERT INTO runtime_rate_bucket(name,tokens) VALUES (?,?) ON CONFLICT DO NOTHING", name, capacity);
            double rate = capacity / (period.toNanos() / 1_000_000_000d);
            return !db.queryForList("""
                    UPDATE runtime_rate_bucket SET tokens=least(?,tokens+
                        greatest(0,extract(epoch FROM clock_timestamp()-refilled_at))*?)-1,
                        refilled_at=clock_timestamp()
                    WHERE name=? AND least(?,tokens+
                        greatest(0,extract(epoch FROM clock_timestamp()-refilled_at))*?)>=1 RETURNING name
                    """, capacity, rate, name, capacity, rate).isEmpty();
        }));
    }

    public void renew() {
        for (Lease lease : held.values()) {
            try {
                if (db.update("UPDATE runtime_lease SET lease_until=clock_timestamp()+interval '120 seconds' "
                        + "WHERE resource=? AND slot=? AND owner=? AND lease_until>=clock_timestamp()",
                        lease.resource, lease.slot, lease.owner) != 1) lease.valid.set(false);
            } catch (RuntimeException ex) {
                // 无法确认续租即停止信任本地所有权；最终写入仍会检查数据库。
                lease.valid.set(false);
            }
        }
    }

    public final class Lease implements AutoCloseable {
        private final String resource;
        private final int slot;
        private final UUID owner;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(String resource, int slot, UUID owner) { this.resource=resource; this.slot=slot; this.owner=owner; }
        public boolean valid() { return valid.get() && !closed.get(); }

        /** 必须位于业务最终写事务中，锁定租约行直到该事务提交。 */
        public void fence() {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Fencing requires a write transaction");
            if (!valid() || db.queryForList("SELECT slot FROM runtime_lease WHERE resource=? AND slot=? AND owner=? "
                    + "AND lease_until>=clock_timestamp() FOR UPDATE", resource, slot, owner).isEmpty()) {
                throw new IllegalStateException("Document write lease lost");
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Runnable release = () -> {
                held.remove(owner);
                tx.executeWithoutResult(status -> db.update("UPDATE runtime_lease SET owner=null,lease_until='epoch' "
                        + "WHERE resource=? AND slot=? AND owner=?", resource, slot, owner));
            };
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { release.run(); }
                });
            } else release.run();
        }
    }

    @PreDestroy public void close() {
        renewal.shutdownNow();
        for (Lease lease : held.values()) {
            try { lease.close(); } catch (RuntimeException ignored) { /* 数据库不可用时租约自然到期。 */ }
        }
    }
}
