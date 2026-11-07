package com.personalos.knowledge.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class KnowledgeIndexJobsBoundaryTest {
 static class Fixture implements AutoCloseable {
  final KnowledgeJobRepository repo=mock(KnowledgeJobRepository.class);
  final KnowledgeDocumentService docs=mock(KnowledgeDocumentService.class);
  final EmbeddingModel model=mock(EmbeddingModel.class);
  final VectorStoreInspector vectors=mock(VectorStoreInspector.class);
  final DocumentWriteLock locks=mock(DocumentWriteLock.class);
  final ModelConcurrencyGate gate=mock(ModelConcurrencyGate.class);
  final TokenBucketRateLimiter rate=mock(TokenBucketRateLimiter.class);
  final JdbcTemplate db=mock(JdbcTemplate.class);
  final SimpleMeterRegistry meters=new SimpleMeterRegistry();
  final KnowledgeIndexJobs jobs;
  final UUID id=UUID.randomUUID();
  Fixture(boolean enabled) {
   var manager=mock(PlatformTransactionManager.class);
   when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
   var properties=new KnowledgeProperties(2,4,.3,8,List.of("md"),1,20,0,true,60,1);
   @SuppressWarnings("unchecked") ObjectProvider<KnowledgeJobNotifier> notifier=mock(ObjectProvider.class);
   jobs=new KnowledgeIndexJobs(repo,docs,model,vectors,locks,gate,rate,new JsonCodec(new ObjectMapper()),db,properties,manager,meters,notifier,enabled);
  }
  KnowledgeJobRepository.Claimed claim(int attempts) {
   return new KnowledgeJobRepository.Claimed(new KnowledgeJobRepository.Job(id,id,false,"a.md","a","RUNNING",attempts,0,"",null,null),UUID.randomUUID(),new byte[]{1});
  }
  public void close(){jobs.close();meters.close();}
 }
 @Test void submissionRejectsInvalidPayloadBeforeQueueOrDocumentWrites() {
  try(var f=new Fixture(false)){
   for(byte[] bytes:new byte[][]{null,new byte[0],new byte[9]})
    assertThatThrownBy(()->f.jobs.submit(f.id,null,"a.md",null,bytes)).isInstanceOf(BusinessException.class);
   for(String name:Arrays.asList(null,"x".repeat(301)))
    assertThatThrownBy(()->f.jobs.submit(f.id,null,name,null,new byte[]{1})).isInstanceOf(BusinessException.class);
   assertThatThrownBy(()->f.jobs.submit(f.id,null,"a.md","x".repeat(301),new byte[]{1})).isInstanceOf(BusinessException.class);
   verifyNoInteractions(f.repo,f.docs);
  }
 }
 @Test void fullQueueDoesNotCreateOrResolveReplacement() {
  try(var f=new Fixture(false)){
   when(f.repo.waitingCount()).thenReturn(20L);
   assertThatThrownBy(()->f.jobs.submit(f.id,UUID.randomUUID(),"a.md",null,new byte[]{1})).isInstanceOf(BusinessException.class);
   verify(f.repo,never()).create(any(),any(),anyBoolean(),anyString(),any(),any(),anyString());
   verifyNoInteractions(f.docs);
   assertThatThrownBy(()->f.jobs.get(f.id)).hasMessageContaining("不存在");
   assertThat(f.jobs.recent()).isEmpty();
  }
 }
 @Test void noWorkNoCapacityAndNoRateDoNotClaimOrEmbed() {
  for(String state:List.of("idle","capacity","rate","race"))try(var f=new Fixture(true)){
   when(f.repo.hasReady()).thenReturn(!state.equals("idle"));
   var lease=mock(ModelConcurrencyGate.Lease.class);
   when(f.gate.tryAcquire()).thenReturn(state.equals("capacity")?null:lease);
   when(f.rate.tryAcquire()).thenReturn(!state.equals("rate"));
   assertThat(f.jobs.runNext()).isFalse();
   verifyNoInteractions(f.docs,f.model,f.vectors);
   if(state.equals("rate")||state.equals("race"))verify(lease).close();
  }
 }
 @Test void retryPolicyDistinguishesBusinessFailuresAndAttemptLimit() {
  var failures=new ArrayList<RuntimeException>();
  failures.add(new IllegalStateException("upstream secret"));
  for(ErrorCode code:List.of(ErrorCode.BUSINESS_CONFLICT,ErrorCode.AI_UPSTREAM_ERROR,ErrorCode.AI_TIMEOUT,ErrorCode.UNSUPPORTED_DOCUMENT))failures.add(new BusinessException(code));
  for(int attempt:List.of(1,3))for(RuntimeException failure:failures)try(var f=new Fixture(true)){
   var claim=f.claim(attempt);
   when(f.repo.hasReady()).thenReturn(true);when(f.gate.tryAcquire()).thenReturn(mock(ModelConcurrencyGate.Lease.class));
   when(f.rate.tryAcquire()).thenReturn(true);when(f.repo.claim()).thenReturn(Optional.of(claim));
   when(f.docs.prepareUpload(anyString(),anyString(),any())).thenThrow(failure);
   assertThat(f.jobs.runNext()).isTrue();
   boolean retry=attempt<3 && (!(failure instanceof BusinessException b)||b.getErrorCode()!=ErrorCode.UNSUPPORTED_DOCUMENT);
   verify(f.repo).fail(eq(claim),eq(retry),argThat(message->!message.contains("secret")));
   verify(f.locks).release(f.id);verifyNoInteractions(f.model,f.vectors);
   assertThat(f.meters.get("personalos.knowledge.jobs.attempts").tag("outcome",retry?"retry":"failed").counter().count()).isEqualTo(1);
  }
 }
 @Test void emptyChunksAndWrongVectorCountCannotPublishIndex() {
  for(boolean empty:List.of(true,false))try(var f=new Fixture(true)){
   var claim=f.claim(1);when(f.repo.hasReady()).thenReturn(true);
   when(f.gate.tryAcquire()).thenReturn(mock(ModelConcurrencyGate.Lease.class));when(f.rate.tryAcquire()).thenReturn(true);
   when(f.repo.claim()).thenReturn(Optional.of(claim));
   var upload=mock(KnowledgeDocumentService.PreparedUpload.class);when(f.docs.prepareUpload(anyString(),anyString(),any())).thenReturn(upload);
   when(f.docs.chunksFor(upload,f.id)).thenReturn(empty?List.of():List.of(new org.springframework.ai.document.Document("content")));
   assertThat(f.jobs.runNext()).isTrue();verify(f.repo).fail(eq(claim),eq(!empty),anyString());verifyNoInteractions(f.vectors);
  }
 }
 @Test void schedulerHonorsRoleCapacityAndShutdown() {
  try(var f=new Fixture(false)){f.jobs.dispatch();verifyNoInteractions(f.repo);}
  try(var f=new Fixture(true)){
   f.jobs.configureRole(new RuntimeRole("api"));f.jobs.dispatch();f.jobs.cleanup();verifyNoInteractions(f.repo);
   f.jobs.configureRole(new RuntimeRole("index"));
   var active=(AtomicInteger)ReflectionTestUtils.getField(f.jobs,"active");active.set(1);f.jobs.dispatch();verifyNoInteractions(f.repo);
   active.set(0);f.jobs.close();f.jobs.dispatch();assertThat(active.get()).isZero();f.jobs.cleanup();verify(f.repo).cleanup();
  }
 }
}
