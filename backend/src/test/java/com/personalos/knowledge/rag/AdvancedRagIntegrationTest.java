package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import com.personalos.knowledge.service.*;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","app.knowledge.rag.enabled=true",
    "app.knowledge.rag.ingestion-review-enabled=false","app.knowledge.search.poll-ms=3600000","app.knowledge.jobs.enabled=false","app.knowledge.min-chunk-size-chars=10","app.knowledge.similarity-threshold=0.0"})
@Testcontainers
class AdvancedRagIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:pg17");
    @Container static org.testcontainers.containers.GenericContainer<?> search=new org.testcontainers.containers.GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("opensearchproject/opensearch:3.9.0"))
        .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true").withEnv("DISABLE_INSTALL_DEMO_CONFIG","true")
        .withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m").withExposedPorts(9200)
        .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/_cluster/health").forPort(9200));
    @org.springframework.test.context.DynamicPropertySource static void searchProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.knowledge.search.enabled",()->true);
        registry.add("app.knowledge.search.endpoint",()->"http://"+search.getHost()+":"+search.getMappedPort(9200));
        registry.add("app.knowledge.search.index",()->"advanced-rag-test");
    }
    @TestConfiguration static class Models {
        @Bean EmbeddingModel embeddingModel(){return new com.personalos.support.DeterministicEmbeddingModel(1024);}
        @Bean ScriptedToolCallingChatModel chatModel(){return new ScriptedToolCallingChatModel();}
    }
    @Autowired KnowledgeDocumentService documents; @Autowired KnowledgeRetrievalService recall;
    @Autowired KnowledgeQaService qa;@Autowired KnowledgeSearchPort port;@Autowired ScriptedToolCallingChatModel model;
    @Autowired com.personalos.knowledge.search.SearchProjection projection;
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Test void enabledApplicationWiresPipelineAndProtectsTwoKnowledgeSourcesEndToEnd() {
        assertThat(port).isInstanceOf(RagRetrievalPipeline.class);
        var a=documents.ingest("a.md","甲知识库","国内差旅住宿报销标准每天五百元，报销须提交发票。".getBytes(StandardCharsets.UTF_8)).id();
        var b=documents.ingest("b.md","乙知识库","国内差旅住宿报销标准每天八百元，报销须提交发票。".getBytes(StandardCharsets.UTF_8)).id();
        projection.sync();assertThat(projection.pending()).isZero();
        var hits=recall.candidates("住宿报销标准",40);
        assertThat(hits).hasSize(2);
        model.enqueueText("{\"queries\":[]}");model.enqueueText("{\"order\":[1,2]}");
        String quote1=hits.get(0).content().contains("五百")?"每天五百元":"每天八百元";
        String quote2=hits.get(1).content().contains("五百")?"每天五百元":"每天八百元";
        model.enqueueText(json.writeValueAsString(Map.of("evidence",List.of(Map.of("index",1,"grade","SUPPORTED"),Map.of("index",2,"grade","SUPPORTED")),
            "conflicts",List.of(Map.of("left",1,"right",2,"leftQuote",quote1,"rightQuote",quote2,"reason","同范围报销金额不同")),"clarification","请说明适用部门和日期")));
        var answer=qa.ask("住宿报销标准",1);
        assertThat(answer.answered()).isFalse();assertThat(answer.retrieval().status()).isEqualTo("CONFLICT");
        assertThat(answer.citations()).hasSize(2);assertThat(answer.citations()).extracting(c->c.documentId()).containsExactlyInAnyOrder(a,b);
        assertThat(answer.answer()).contains("不同说法","部门和日期");
        assertThat(model.receivedInstructions()).hasSize(3);
        assertThat(db.queryForObject("SELECT count(*) FROM knowledge_conflict WHERE status='OPEN'",Integer.class)).isEqualTo(1);
        documents.delete(a);
        assertThat(recall.candidates("住宿报销标准",40)).noneMatch(hit->hit.documentId().equals(a));
        projection.sync();assertThat(projection.pending()).isZero();
        assertThat(recall.candidates("住宿报销标准",40)).extracting(hit->hit.documentId()).containsOnly(b);
        search.stop();
        assertThat(recall.candidates("住宿报销标准",40)).extracting(hit->hit.documentId()).containsOnly(b);
    }
}
