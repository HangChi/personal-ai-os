package com.personalos.knowledge.search;

import com.personalos.knowledge.service.VectorStoreInspector;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import com.personalos.common.config.RuntimeRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Outbox 与业务写入同事务；网络请求在事务外，有外部版本号及条件 ACK。 */
public final class SearchProjection {
    private final JdbcTemplate db;
    private final VectorStoreInspector inspector;
    private final OpenSearchGateway gateway;
    private final MeterRegistry meters;
    private final RuntimeRole role;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("search-projection").factory());

    public SearchProjection(JdbcTemplate db, VectorStoreInspector inspector, OpenSearchGateway gateway,
                            MeterRegistry meters, RuntimeRole role) {
        this.db=db; this.inspector=inspector; this.gateway=gateway; this.meters=meters; this.role=role;
        meters.gauge("personalos.knowledge.search.pending",this,SearchProjection::pending);
        if (role.allows("api")) reindex();
    }

    public long pending() {
        return db.queryForObject("SELECT count(*) FROM knowledge_search_outbox",Long.class);
    }

    @Scheduled(fixedDelayString="${app.knowledge.search.poll-ms:2000}")
    public void sync() {
        if (!role.allows("delivery") || !busy.compareAndSet(false,true)) return;
        try {
            executor.execute(() -> {
                try { projectBatch(); } finally { busy.set(false); }
            });
        } catch (RejectedExecutionException ex) { busy.set(false); }
    }

    public void projectBatch() {
        for (int i=0; i<20 && !Thread.currentThread().isInterrupted(); i++) {
            UUID token=UUID.randomUUID();
            var rows=db.query("""
                    WITH ready AS (SELECT chunk_id FROM knowledge_search_outbox WHERE available_at<=now()
                        ORDER BY revision FOR UPDATE SKIP LOCKED LIMIT 1)
                    UPDATE knowledge_search_outbox o SET claim_token=?,available_at=now()+interval '60 seconds',
                        attempts=attempts+1 FROM ready WHERE o.chunk_id=ready.chunk_id
                    RETURNING o.chunk_id,o.revision,o.attempts
                    """, (rs,n)->new Claim(rs.getObject(1,UUID.class),rs.getLong(2),rs.getInt(3)),token);
            if (rows.isEmpty()) return;
            Claim row=rows.getFirst();
            try {
                var chunks=inspector.projectionChunks(List.of(row.id()));
                if (chunks.isEmpty()) gateway.project(row.id(),row.revision(),null,null);
                else {
                    var chunk=chunks.getFirst();
                    gateway.project(row.id(),row.revision(),chunk.content(),chunk.embedding());
                }
                db.update("DELETE FROM knowledge_search_outbox WHERE chunk_id=? AND revision=? AND claim_token=?",
                        row.id(),row.revision(),token);
                meters.counter("personalos.knowledge.search.projections","result","success").increment();
            } catch (RuntimeException ex) {
                int delay=Math.min(300,1 << Math.min(9,row.attempts()));
                db.update("UPDATE knowledge_search_outbox SET claim_token=NULL,available_at=now()+?*interval '1 second' "
                        +"WHERE chunk_id=? AND revision=? AND claim_token=?",delay,row.id(),row.revision(),token);
                meters.counter("personalos.knowledge.search.projections","result","retry").increment();
            }
        }
    }

    private record Claim(UUID id,long revision,int attempts) { }

    @PreDestroy public void close() { executor.shutdownNow(); }

    public int reindex() {
        return db.update("INSERT INTO knowledge_search_outbox(chunk_id) SELECT id FROM "+inspector.schemaName()+"."+inspector.tableName()
                +" ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'),changed_at=now()");
    }
}
