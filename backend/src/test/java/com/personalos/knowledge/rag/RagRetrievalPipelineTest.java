package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
class RagRetrievalPipelineTest {
    final ObjectMapper json=new ObjectMapper();
    final KnowledgeRetrievalService recall=mock(KnowledgeRetrievalService.class);
    final RagModelGateway model=mock(RagModelGateway.class);
    final KnowledgeHitDto a=new KnowledgeHitDto(UUID.randomUUID(),"甲库差旅办法","a.md",0,"住宿报销标准每天五百元。",.8);
    final KnowledgeHitDto b=new KnowledgeHitDto(UUID.randomUUID(),"乙库差旅办法","b.md",0,"住宿报销标准每天八百元。",.7);
    RagRetrievalPipeline pipeline;
    @BeforeEach void setup() {
        var properties=new KnowledgeProperties(1024,4,.3,50000,List.of("md"),10,1200,0,true,60,1);
        pipeline=new RagRetrievalPipeline(recall,model,new EvidenceReview(model,json),properties,json,new SimpleMeterRegistry(),true,20000);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,b));
    }
    void responses(String rewrite,String order,String evidence) {
        when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree(rewrite),json.readTree(order),json.readTree(evidence));
    }
    @Test void conflictingSourcesSurviveTopOneAndAskForClarification() {
        responses("{\"queries\":[\"住宿报销限额\"]}","{\"order\":[1,2]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"SUPPORTED"}],
             "conflicts":[{"left":1,"right":2,"leftQuote":"每天五百元","rightQuote":"每天八百元","reason":"相同费用标准不同"}],
             "clarification":"请说明适用部门与出差时间"}
            """);
        var result=pipeline.retrieve("报销多少？",1);
        assertThat(result.status()).isEqualTo("CONFLICT");assertThat(result.hits()).containsExactly(a,b);
        assertThat(result.queries()).containsExactly("报销多少？","住宿报销限额");
        assertThat(result.guardedAnswer()).contains("[1]","[2]","适用部门");
        verify(recall).candidates("报销多少？",40);verify(recall).candidates("住宿报销限额",40);
    }
    @Test void invalidRerankFallsBackWithoutLosingSources() {
        responses("{\"queries\":[]}","{\"order\":[99]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"IRRELEVANT"}],"conflicts":[],"clarification":""}
            """);
        var result=pipeline.retrieve("问题",4);assertThat(result.status()).isEqualTo("READY");
        assertThat(result.hits()).containsExactly(a);assertThat(result.warnings()).anyMatch(w->w.contains("重排"));
    }
    @Test void fabricatedConflictQuoteIsRejectedAndGenerationIsGuarded() {
        responses("{\"queries\":[]}","{\"order\":[1,2]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"SUPPORTED"}],
             "conflicts":[{"left":1,"right":2,"leftQuote":"不存在的原文","rightQuote":"每天八百元"}],"clarification":""}
            """);
        var result=pipeline.retrieve("问题",4);assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.disagreements()).isEmpty();assertThat(result.guarded()).isTrue();
    }
    @Test void emptyLibraryDoesNotCallGeneration() {
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of());
        assertThat(pipeline.retrieve("问题",4).status()).isEqualTo("INSUFFICIENT");verifyNoInteractions(model);
    }
    @Test void unavailableStagesRetainOriginalAndRefuseToInventConclusion() {
        when(model.call(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("timeout"));
        var result=pipeline.retrieve("原问题",4);assertThat(result.queries()).containsExactly("原问题");
        assertThat(result.status()).isEqualTo("UNVERIFIED");assertThat(result.hits()).contains(a,b);
    }
    @Test void disjointConditionsRequestClarificationWithoutInventingConflict() {
        responses("{\"queries\":[]}","{\"order\":[2,1]}","""
            {"evidence":[{"index":1,"grade":"PARTIAL"},{"index":2,"grade":"PARTIAL"}],"conflicts":[],"clarification":"你询问的是哪一年制度？"}
            """);
        var result=pipeline.retrieve("问题",1);assertThat(result.status()).isEqualTo("CLARIFY");assertThat(result.disagreements()).isEmpty();
        assertThat(result.hits()).hasSize(2);
    }
}
