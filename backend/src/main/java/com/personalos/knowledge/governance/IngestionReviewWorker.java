package com.personalos.knowledge.governance;

import com.personalos.common.web.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.EvidenceReview;
import com.personalos.knowledge.service.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Incremental candidate review; never labels the entire knowledge base conflict-free. */
@Component
@ConditionalOnProperty(name={"app.knowledge.rag.enabled","app.knowledge.rag.ingestion-review-enabled"},havingValue="true",matchIfMissing=true)
public class IngestionReviewWorker {
    private final JdbcTemplate db; private final KnowledgeGovernance governance; private final EvidenceReview review;
    private final KnowledgeRetrievalService retrieval; private final VectorStoreInspector vectors;
    private final ModelConcurrencyGate gate; private final TokenBucketRateLimiter rate;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ingestion-review").factory());
    private long reviewTimeout=12000;
    @org.springframework.beans.factory.annotation.Autowired
    void configureReviewTimeout(@org.springframework.beans.factory.annotation.Value("${app.knowledge.rag.evidence-timeout-ms:12000}") long timeout) {
        reviewTimeout=Math.max(1000,Math.min(30000,timeout));
    }
    private final AtomicBoolean busy=new AtomicBoolean();
    public IngestionReviewWorker(JdbcTemplate db,KnowledgeGovernance governance,EvidenceReview review,KnowledgeRetrievalService retrieval,
            VectorStoreInspector vectors,ModelConcurrencyGate gate,TokenBucketRateLimiter rate) {
        this.db=db;this.governance=governance;this.review=review;this.retrieval=retrieval;this.vectors=vectors;this.gate=gate;this.rate=rate;
    }
    @Scheduled(fixedDelayString="${app.knowledge.rag.review-poll-ms:5000}",initialDelay=10000)
    public void dispatch(){if(busy.compareAndSet(false,true))executor.submit(()->{try{runNext();}finally{busy.set(false);}});}
    public boolean runNext() {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_review_job WHERE next_attempt_at<=now())",Boolean.class)))return false;
        var permit=gate.tryAcquire();if(permit==null)return false;
        try(permit) {
            if(!rate.tryAcquire())return false;
            UUID token=UUID.randomUUID();
            var jobs=db.queryForList("WITH ready AS (SELECT document_id FROM knowledge_review_job WHERE next_attempt_at<=now() ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE knowledge_review_job j SET next_attempt_at=now()+interval '60 seconds',lease_token=? FROM ready WHERE j.document_id=ready.document_id RETURNING j.*",token);
            if(jobs.isEmpty())return false;
            var job=jobs.getFirst();UUID id=(UUID)job.get("document_id");long revision=((Number)job.get("revision")).longValue();int cursor=((Number)job.get("chunk_cursor")).intValue();
            try {
                var chunks=vectors.exportChunks(id,cursor,1);
                var source=governance.sources(List.of(id)).get(id);
                if(source==null || source.revision()!=revision)return true;
                if(chunks.isEmpty()) {
                    db.update("UPDATE knowledge_source SET review_state='CHECKED_CANDIDATES' WHERE document_id=? AND revision=? AND EXISTS(SELECT 1 FROM knowledge_review_job j WHERE j.document_id=knowledge_source.document_id AND j.lease_token=?)",id,revision,token);
                    db.update("DELETE FROM knowledge_review_job WHERE document_id=? AND revision=? AND lease_token=?",id,revision,token);return true;
                }
                var raw=retrieval.candidates(chunks.getFirst().substring(0,Math.min(1800,chunks.getFirst().length())),20);
                var candidates=new ArrayList<KnowledgeHitDto>();
                String title=db.queryForObject("SELECT title FROM knowledge_document WHERE id=?",String.class,id);
                candidates.add(new KnowledgeHitDto(id,title,"",cursor,chunks.getFirst(),null).withSource(source));
                candidates.addAll(governance.enrich(raw).stream().filter(h->!h.documentId().equals(id)).limit(11).toList());
                if(candidates.size()>1) governance.record(review.review("核查新增资料与已有资料对同一事实的不同说法；所有当前资料均为核查对象。",candidates,reviewTimeout).conflicts());
                db.update("UPDATE knowledge_source SET review_state='PARTIAL' WHERE document_id=? AND revision=? AND EXISTS(SELECT 1 FROM knowledge_review_job j WHERE j.document_id=knowledge_source.document_id AND j.lease_token=?)",id,revision,token);
                db.update("UPDATE knowledge_review_job SET chunk_cursor=chunk_cursor+1,next_attempt_at=now() WHERE document_id=? AND revision=? AND lease_token=?",id,revision,token);
            } catch(RuntimeException ex) {
                db.update("UPDATE knowledge_source SET review_state='UNAVAILABLE' WHERE document_id=? AND revision=? AND EXISTS(SELECT 1 FROM knowledge_review_job j WHERE j.document_id=knowledge_source.document_id AND j.lease_token=?)",id,revision,token);
                db.update("UPDATE knowledge_review_job SET next_attempt_at=now()+interval '5 minutes' WHERE document_id=? AND revision=? AND lease_token=?",id,revision,token);
            }
            return true;
        }
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
