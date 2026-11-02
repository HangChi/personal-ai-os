package com.personalos.knowledge.search;

import com.personalos.knowledge.service.VectorStoreInspector;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Outbox 与业务写入同事务；网络请求在事务外，有外部版本号及条件 ACK。 */
public final class SearchProjection {
    private final JdbcTemplate db;
    private final VectorStoreInspector inspector;
    private final OpenSearchGateway gateway;
    private final MeterRegistry meters;
    public SearchProjection(JdbcTemplate db,VectorStoreInspector inspector,OpenSearchGateway gateway,MeterRegistry meters) {
        this.db=db; this.inspector=inspector; this.gateway=gateway; this.meters=meters;
        meters.gauge("personalos.knowledge.search.pending",this,s->s.pending());
        // A fresh index name or restored database must never look successfully empty.
        reindex();
    }
    public long pending(){return db.queryForObject("SELECT count(*) FROM knowledge_search_outbox",Long.class);}
    @Scheduled(fixedDelayString="${app.knowledge.search.poll-ms:2000}")
    public void sync() {
        var rows=db.query("SELECT chunk_id,revision FROM knowledge_search_outbox ORDER BY revision LIMIT 50",
                (rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getLong(2)));
        for(var row:rows) {
            try {
                var chunks=inspector.projectionChunks(List.of(row.getKey()));
                if(chunks.isEmpty()) gateway.project(row.getKey(),row.getValue(),null,null);
                else {var chunk=chunks.getFirst(); gateway.project(row.getKey(),row.getValue(),chunk.content(),chunk.embedding());}
                db.update("DELETE FROM knowledge_search_outbox WHERE chunk_id=? AND revision=?",row.getKey(),row.getValue());
                meters.counter("personalos.knowledge.search.projections","result","success").increment();
            } catch(RuntimeException ex) { meters.counter("personalos.knowledge.search.projections","result","retry").increment(); break; }
        }
    }
    public int reindex() {
        return db.update("INSERT INTO knowledge_search_outbox(chunk_id) SELECT id FROM "+inspector.schemaName()+"."+inspector.tableName()
                +" ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'),changed_at=now()");
    }
}
