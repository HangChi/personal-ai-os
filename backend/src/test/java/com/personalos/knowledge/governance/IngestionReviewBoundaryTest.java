package com.personalos.knowledge.governance;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.web.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.EvidenceReview;
import com.personalos.knowledge.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
class IngestionReviewBoundaryTest {
 @Test void reviewAcquisitionAndStaleSourcesNeverWriteFindings(){
  for(String mode:List.of("idle","capacity","rate","claim race","deleted source","stale revision","end","chunk","conflicts","failure","lost lease")){
   var db=mock(JdbcTemplate.class);var governance=mock(KnowledgeGovernance.class);var review=mock(EvidenceReview.class);
   var retrieval=mock(KnowledgeRetrievalService.class);var vectors=mock(VectorStoreInspector.class);
   var gate=mock(ModelConcurrencyGate.class);var rate=mock(TokenBucketRateLimiter.class);var manager=mock(PlatformTransactionManager.class);
   when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
   when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(!mode.equals("idle"));
   var permit=mock(ModelConcurrencyGate.Lease.class);when(gate.tryAcquire()).thenReturn(mode.equals("capacity")?null:permit);when(rate.tryAcquire()).thenReturn(!mode.equals("rate"));
   UUID id=UUID.randomUUID();var job=Map.<String,Object>of("document_id",id,"revision",2L,"chunk_cursor",0);
   when(db.queryForList(startsWith("WITH ready"),any(Object[].class))).thenReturn(mode.equals("claim race")?List.of():List.of(job));
   when(db.queryForList(startsWith("SELECT document_id FROM knowledge_review_job"),any(Object[].class))).thenReturn(mode.equals("lost lease")?List.of():List.of(job));
   var source=new SourceInfo(null,"","v1","",null,null,"USER","ACTIVE",mode.equals("stale revision")?1:2,"PENDING");
   when(governance.sources(List.of(id))).thenReturn(mode.equals("deleted source")?Map.of():Map.of(id,source));
   boolean chunk=Set.of("chunk","conflicts","failure","lost lease").contains(mode);when(vectors.exportChunks(id,0,1)).thenReturn(chunk?List.of("evidence"):List.of());
   if(mode.equals("failure"))when(vectors.exportChunks(id,0,1)).thenThrow(new IllegalStateException("private error"));
   when(db.queryForObject(anyString(),eq(String.class),eq(id))).thenReturn("title");
   var own=new KnowledgeHitDto(id,"own","a.md",0,"text",.8);var other=new KnowledgeHitDto(UUID.randomUUID(),"other","b.md",0,"text",.7);
   when(governance.enrich(anyList())).thenReturn(mode.equals("conflicts")?List.of(own,other):List.of(own));
   when(review.review(anyString(),anyList(),anyLong())).thenReturn(new EvidenceReview.Review(List.of(other),List.of(),""));
   var worker=new IngestionReviewWorker(db,governance,review,retrieval,vectors,gate,rate,manager);
   try{
    worker.configureReviewTimeout(999999);worker.configureRole(new RuntimeRole("api"));worker.dispatch();
    boolean claimed=!Set.of("idle","capacity","rate","claim race").contains(mode);assertThat(worker.runNext()).as(mode).isEqualTo(claimed);
    if(mode.equals("end"))verify(db).update(startsWith("DELETE FROM knowledge_review_job"),any(Object[].class));
    else if(mode.equals("failure"))verify(db).update(contains("last_error='Review failed'"),any(Object[].class));
    else if(mode.equals("chunk")||mode.equals("conflicts"))verify(governance).record(List.of());
    else verify(governance,never()).record(anyList());
    if(mode.equals("lost lease"))verify(db,never()).update(anyString(),any(Object[].class));
   }finally{worker.close();}
  }
 }

 @Test void dispatchIsSingleFlightAndMetricsReadActualQueueValues() throws Exception {
  var db=mock(JdbcTemplate.class);var started=new java.util.concurrent.CountDownLatch(1);var unblock=new java.util.concurrent.CountDownLatch(1);
  when(db.queryForObject(anyString(),eq(Boolean.class))).thenAnswer(call->{started.countDown();unblock.await(2,java.util.concurrent.TimeUnit.SECONDS);return false;});
  when(db.queryForObject(anyString(),eq(Long.class))).thenReturn(2L);when(db.queryForObject(anyString(),eq(Double.class))).thenReturn(3.0);
  var worker=new IngestionReviewWorker(db,mock(KnowledgeGovernance.class),mock(EvidenceReview.class),mock(KnowledgeRetrievalService.class),mock(VectorStoreInspector.class),mock(ModelConcurrencyGate.class),mock(TokenBucketRateLimiter.class),mock(PlatformTransactionManager.class));
  var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  try{
   worker.configureMetrics(meters);worker.configureRole(new RuntimeRole("review"));worker.dispatch();assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();worker.dispatch();
   assertThat(meters.get("personalos.knowledge.review.ready").gauge().value()).isEqualTo(2.0);assertThat(meters.get("personalos.knowledge.review.failed").gauge().value()).isEqualTo(2.0);assertThat(meters.get("personalos.knowledge.review.oldest.ready.seconds").gauge().value()).isEqualTo(3.0);
  }finally{unblock.countDown();worker.close();meters.close();}
 }
}
