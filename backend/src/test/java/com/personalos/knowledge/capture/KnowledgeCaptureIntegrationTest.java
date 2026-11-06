package com.personalos.knowledge.capture;
import static org.assertj.core.api.Assertions.*;
import com.personalos.support.*;
import com.personalos.knowledge.jobs.KnowledgeIndexJobs;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","app.knowledge.jobs.enabled=false","app.rate-limit.enabled=false","app.knowledge.min-chunk-size-chars=10"})
@Testcontainers
class KnowledgeCaptureIntegrationTest extends ContainerContextTest {
 @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
 static class Fixture extends PublicWebFetcher {String content="旧版公开资料".repeat(30);public Page fetch(String url){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new Page(canonical(url).toString(),"公开资料",content);}}
 static class Model extends DeterministicEmbeddingModel {boolean fail;Model(){super(1024);}public EmbeddingResponse call(EmbeddingRequest r){if(fail)throw new IllegalStateException("private upstream");return super.call(r);}}
 @TestConfiguration static class Config {@Bean @Primary Fixture fixture(){return new Fixture();}final Model model=new Model();@Bean EmbeddingModel embeddingModel(){return model;}}
 private final KnowledgeCaptureService service;private final KnowledgeIndexJobs jobs;private final JdbcTemplate jdbc;private final Fixture fixture;private final Model model;
 @Autowired KnowledgeCaptureIntegrationTest(KnowledgeCaptureService s,KnowledgeIndexJobs j,JdbcTemplate d,Fixture f,Config config){service=s;jobs=j;jdbc=d;fixture=f;model=config.model;}
 @BeforeEach void clean(){jdbc.execute("TRUNCATE knowledge_capture,knowledge_index_job,knowledge_document CASCADE");fixture.content="旧版公开资料".repeat(30);model.fail=false;}
 @Test void concurrentDedupAtomicPromotionAndFailureKeepsPriorVersion() throws Exception {
  KnowledgeCaptureService.Capture first;
  try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->service.capture("https://example.com/article#one"));var b=pool.submit(()->service.capture("https://EXAMPLE.com:443/article#two"));first=a.get();assertThat(b.get().id()).isEqualTo(first.id());}
  assertThat(first.currentVersion()).isZero();assertThat(first.documentId()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_job",Integer.class)).isEqualTo(1);
  jobs.runNext();var success=service.get(first.id());assertThat(success.currentVersion()).isEqualTo(1);assertThat(success.status()).isEqualTo("SUCCEEDED");
  assertThat(service.refresh(first.id()).pendingJobId()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_capture_version",Integer.class)).isEqualTo(1);
  fixture.content="新版公开资料".repeat(30);service.refresh(first.id());model.fail=true;jobs.runNext();
  assertThat(service.get(first.id()).currentVersion()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE content LIKE '%旧版%'",Integer.class)).isPositive();
  model.fail=false;jdbc.execute("UPDATE knowledge_index_job SET available_at=now()-interval '1 second'");jobs.runNext();
  assertThat(service.get(first.id()).currentVersion()).isEqualTo(2);assertThat(service.get(first.id()).documentId()).isEqualTo(success.documentId());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document",Integer.class)).isEqualTo(1);assertThat(service.versions(first.id()).get("total")).isEqualTo(2L);
 }
}
