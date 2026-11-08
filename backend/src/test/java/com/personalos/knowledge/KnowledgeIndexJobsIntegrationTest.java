package com.personalos.knowledge;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.*;
import com.personalos.knowledge.jobs.*;
import com.personalos.support.DeterministicEmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none",
        "app.knowledge.jobs.enabled=false","app.rate-limit.enabled=false","app.knowledge.min-chunk-size-chars=10"})
@Testcontainers
class KnowledgeIndexJobsIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
    static class Model extends DeterministicEmbeddingModel {
        int failures;
        boolean wrongDimensions;
        Runnable before=()->{};
        Model(){super(1024);}
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            before.run();
            if (failures-->0) throw new IllegalStateException("secret upstream error");
            if (wrongDimensions) return new EmbeddingResponse(List.of(new Embedding(new float[]{1},0)));
            return super.call(request);
        }
    }
    @TestConfiguration static class Config {
        final Model model=new Model();
        @Bean EmbeddingModel embeddingModel(){return model;}
    }
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired Config config;
    @Autowired KnowledgeIndexJobs jobs;
    @Autowired KnowledgeJobRepository repository;
    @Autowired JdbcTemplate jdbc;
    private byte[] text(String value){return value.repeat(20).getBytes(StandardCharsets.UTF_8);}
    private KnowledgeJobRepository.Job submit(String value){return jobs.submit(UUID.randomUUID(),null,"test.md",value,text(value));}
    @BeforeEach void clean() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("test");
        jdbc.execute("delete from knowledge_index_job"); jdbc.execute("delete from vector_store"); jdbc.execute("delete from knowledge_document");
        config.model.failures=0; config.model.wrongDimensions=false; config.model.before=()->{};
    }
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
    @Test void httpSubmissionReturnsAcceptedBeforeIndexingAndProgressSurvivesReconnect() throws Exception {
        UUID id=UUID.randomUUID();
        String boundary="job-test-boundary";
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.md\"\r\n"
                +"Content-Type: text/markdown\r\n\r\n"+"用于后台索引的内容".repeat(20)+"\r\n--"+boundary+"--\r\n";
        String url="http://127.0.0.1:"+port+"/api/v1/knowledge/jobs";
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Content-Type","multipart/form-data; boundary="+boundary)
                    .header("Idempotency-Key",id.toString())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).contains(id.toString(),"PENDING");
            assertThat(count("knowledge_document")).isZero();
            jobs.runNext();
            var progress=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url+"/"+id)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(progress.statusCode()).isEqualTo(200);
            assertThat(progress.body()).contains("SUCCEEDED");
        }
    }
    @Test void queuedUploadCommitsDocumentVectorsAndReceiptTogetherAndIsIdempotent() {
        UUID id=UUID.randomUUID();
        var job=jobs.submit(id,null,"test.md","title",text("完整文档内容"));
        assertThat(jobs.submit(id,null,"test.md","title",text("完整文档内容")).id()).isEqualTo(id);
        assertThatThrownBy(()->jobs.submit(id,null,"test.md","different",text("完整文档内容"))).hasMessageContaining("幂等键");
        assertThat(count("knowledge_document")).isZero();
        config.model.before=()->assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jobs.runNext()).isTrue();
        assertThat(jobs.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(jobs.get(id).result()).contains(job.documentId().toString());
        assertThat(count("knowledge_document")).isEqualTo(1);
        assertThat(count("vector_store")).isPositive();
        assertThat(jdbc.queryForObject("select payload is null from knowledge_index_job where id=?",Boolean.class,id)).isTrue();
        assertThat(jobs.runNext()).isFalse();
    }
    @Test void queuedUploadAcceptsFilesAbovePreviousFiveMiBLimit() {
        byte[] content = new byte[6 * 1024 * 1024];
        Arrays.fill(content, (byte) 'a');
        var job = jobs.submit(UUID.randomUUID(), null, "large.txt", null, content);
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(count("knowledge_document")).isZero();
    }
    @Test void replacementKeepsOldContentUntilNewIndexAndReceiptCommit() {
        var original=submit("旧版本内容"); jobs.runNext();
        var replace=jobs.submit(UUID.randomUUID(),original.documentId(),"new.md",null,text("新版本内容"));
        config.model.before=()->assertThat(jdbc.queryForObject("select title from knowledge_document",String.class)).isEqualTo("旧版本内容");
        config.model.failures=1;
        jobs.runNext();
        assertThat(jobs.get(replace.id()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select title from knowledge_document",String.class)).isEqualTo("旧版本内容");
        jdbc.execute("update knowledge_index_job set available_at=now()-interval '1 second'");
        jobs.runNext();
        assertThat(jobs.get(replace.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(count("knowledge_document")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from knowledge_document",String.class)).isEqualTo("new");
        assertThat(jdbc.queryForObject("select count(*) from vector_store where content like '%旧版本%'",Integer.class)).isZero();
    }
    @Test void expiredClaimRecoversAndOldWorkerCannotWriteOrOverwriteReceipt() {
        var job=submit("崩溃恢复内容");
        var old=repository.claim().orElseThrow();
        jdbc.execute("update knowledge_index_job set lease_until=now()-interval '1 second'");
        assertThat(jobs.runNext()).isTrue();
        assertThat(jobs.get(job.id()).attempts()).isEqualTo(2);
        assertThat(jobs.get(job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(repository.renew(old)).isFalse();
        assertThat(repository.owns(old)).isFalse();
        repository.fail(old,false,"旧结果");
        assertThat(jobs.get(job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(count("knowledge_document")).isEqualTo(1);
    }
    @Test void losingLeaseDuringEmbeddingFencesAllIndexWrites() {
        var job=submit("旧执行不能写入");
        config.model.before=()->{
            jdbc.execute("update knowledge_index_job set lease_until=now()-interval '1 second'");
            assertThat(repository.claim()).isPresent();
        };
        jobs.runNext();
        assertThat(count("knowledge_document")).isZero();
        assertThat(count("vector_store")).isZero();
        assertThat(jobs.get(job.id()).status()).isEqualTo("RUNNING");
    }
    @Test void vectorWriteFailureRollsBackDocumentAndRetriesAtMostThreeTimes() {
        var job=submit("向量失败回滚"); config.model.wrongDimensions=true;
        for(int attempt=1;attempt<=3;attempt++) {
            jdbc.execute("update knowledge_index_job set available_at=now()-interval '1 second'");
            jobs.runNext();
            assertThat(count("knowledge_document")).isZero();
            assertThat(count("vector_store")).isZero();
            assertThat(jobs.get(job.id()).attempts()).isEqualTo(attempt);
        }
        assertThat(jobs.get(job.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.get(job.id()).error()).doesNotContain("SQL","secret");
        assertThat(jobs.runNext()).isFalse();
    }
    @Test void claimNeverReturnsSameTaskToTwoWorkers() throws Exception {
        submit("第一份任务"); submit("第二份任务");
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->repository.claim().orElseThrow());
            var b=executor.submit(()->repository.claim().orElseThrow());
            assertThat(a.get().job().id()).isNotEqualTo(b.get().job().id());
        }
    }
}
