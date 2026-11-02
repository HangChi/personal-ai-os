package com.personalos.knowledge.governance;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.*;
import com.personalos.knowledge.service.*;
import com.personalos.knowledge.search.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","app.knowledge.min-chunk-size-chars=10","app.knowledge.jobs.enabled=false"})
@Testcontainers
class KnowledgeGovernanceIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:pg17");
    @TestConfiguration static class Models {
        @Bean EmbeddingModel embeddingModel(){return new com.personalos.support.DeterministicEmbeddingModel(1024);}
        @Bean com.personalos.support.ScriptedToolCallingChatModel chatModel(){return new com.personalos.support.ScriptedToolCallingChatModel();}
    }
    @Autowired KnowledgeGovernance governance; @Autowired KnowledgeDocumentService documents;
    @Autowired VectorStoreInspector vectors; @Autowired JdbcTemplate db;
    UUID a,b;
    @BeforeEach void seed() {
        db.update("DELETE FROM vector_store");db.update("DELETE FROM knowledge_document");db.update("DELETE FROM knowledge_conflict_audit");
        db.update("DELETE FROM knowledge_search_outbox");
        a=documents.ingest("a.md","甲库","住宿报销标准每天五百元，适用于国内差旅住宿费用。".getBytes(StandardCharsets.UTF_8)).id();
        b=documents.ingest("b.md","乙库","住宿报销标准每天八百元，适用于国内差旅住宿费用。".getBytes(StandardCharsets.UTF_8)).id();
    }
    List<KnowledgeHitDto> hits(){return governance.enrich(List.of(
        new KnowledgeHitDto(a,"甲库","a.md",0,vectors.chunksByDocumentId(a).getFirst(),.9),
        new KnowledgeHitDto(b,"乙库","b.md",0,vectors.chunksByDocumentId(b).getFirst(),.8)));}
    RagResult.Disagreement disagreement(List<KnowledgeHitDto> h){return new RagResult.Disagreement(h.get(0),h.get(1),"每天五百元","每天八百元","同一标准给出不同金额");}
    @Test void sourceScopeVersionAndDateAreValidatedAndAuditable() {
        var space=governance.createSpace("财务制度");
        var changed=governance.update(a,new KnowledgeGovernance.Metadata(space.id(),"2026版","上海研发部",LocalDate.parse("2026-01-01"),null,"财务发布","ACTIVE"));
        assertThat(changed.spaceName()).isEqualTo("财务制度");assertThat(changed.reviewState()).isEqualTo("PENDING");
        assertThatThrownBy(()->governance.update(a,new KnowledgeGovernance.Metadata(space.id(),"","",LocalDate.parse("2026-02-01"),LocalDate.parse("2026-01-01"),"","ACTIVE"))).hasMessageContaining("日期");
        governance.update(a,new KnowledgeGovernance.Metadata(space.id(),"2026版","",null,null,"","SUPERSEDED"));
        assertThat(hits()).extracting(KnowledgeHitDto::documentId).doesNotContain(a);
    }
    @Test void conflictHasBothQuotesHumanDecisionAndRevisionInvalidation() {
        var h=hits();var conflict=disagreement(h);governance.record(List.of(conflict));governance.record(List.of(conflict));
        var stored=governance.conflicts();assertThat(stored).hasSize(1);assertThat(stored.getFirst().status()).isEqualTo("OPEN");
        assertThatThrownBy(()->governance.resolve(stored.getFirst().id(),new KnowledgeGovernance.Resolution("PREFER_SOURCE",UUID.randomUUID(),"测试"))).hasMessageContaining("双方");
        governance.resolve(stored.getFirst().id(),new KnowledgeGovernance.Resolution("PREFER_SOURCE",b,"经财务确认，以乙库为准"));
        assertThat(db.queryForObject("SELECT count(*) FROM knowledge_conflict_audit",Integer.class)).isEqualTo(1);
        var warnings=new ArrayList<String>();var review=governance.applyDecisions(new EvidenceReview.Review(h,List.of(conflict),""),h,warnings);
        assertThat(review.conflicts()).isEmpty();assertThat(review.relevant()).containsExactly(h.get(1));assertThat(warnings).isNotEmpty();
        var info=h.get(0).source();governance.update(a,new KnowledgeGovernance.Metadata(info.spaceId(),"新版本","",null,null,"","ACTIVE"));
        assertThat(governance.conflicts().getFirst().status()).isEqualTo("STALE");
        governance.record(List.of(conflict));assertThat(governance.conflicts()).hasSize(1);
        assertThatThrownBy(()->governance.resolve(stored.getFirst().id(),new KnowledgeGovernance.Resolution("KEEP_BOTH",null,"复核"))).hasMessageContaining("变化");
    }
    @Test void existingOpenFindingExpandsOpposingSourceAndCannotBeMissedByNextModel() {
        var h=hits();governance.record(List.of(disagreement(h)));
        var expanded=governance.expandConflictingSources(List.of(h.get(0)));assertThat(expanded).extracting(KnowledgeHitDto::documentId).contains(a,b);
        var reviewed=governance.applyDecisions(new EvidenceReview.Review(h,List.of(),""),h,new ArrayList<>());
        assertThat(reviewed.conflicts()).hasSize(1);
    }
    @Test void projectionFailureRetainsOutboxAndConcurrentChangeCannotBeAcknowledged() {
        var gateway=mock(OpenSearchGateway.class);var projection=new SearchProjection(db,vectors,gateway,new SimpleMeterRegistry());
        doThrow(new IllegalStateException("unavailable")).when(gateway).project(any(),anyLong(),any(),any());
        long pending=projection.pending();assertThat(pending).isPositive();projection.sync();assertThat(projection.pending()).isEqualTo(pending);
        reset(gateway);
        doAnswer(invocation->{db.update("UPDATE knowledge_search_outbox SET revision=nextval('knowledge_search_revision') WHERE chunk_id=?",invocation.getArgument(0,UUID.class));return null;})
            .when(gateway).project(any(),anyLong(),any(),any());
        projection.sync();assertThat(projection.pending()).isEqualTo(pending);
        reset(gateway);projection.sync();assertThat(projection.pending()).isZero();
        documents.delete(a);assertThat(projection.pending()).isPositive();projection.sync();assertThat(projection.pending()).isZero();
        verify(gateway,atLeastOnce()).project(any(),anyLong(),isNull(),isNull());
    }
}
