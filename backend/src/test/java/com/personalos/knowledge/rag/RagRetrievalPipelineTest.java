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
        when(recall.hasContent()).thenReturn(true);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,b));
    }
    void responses(String rewrite,String order,String evidence) {
        when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree(rewrite),json.readTree(order),json.readTree(evidence));
    }
    @Test void conversationActionsSkipIrrelevantEvidenceAndBoundHistory() {
        when(model.call(anyString(), anyString(), anyLong())).thenAnswer(call -> {
            assertThat((Long) call.getArgument(2)).isBetween(1L, 4000L);
            var input = json.readTree((String) call.getArgument(1));
            assertThat(input.path("history").size()).isEqualTo(6);
            assertThat(input.path("history").get(5).path("content").asString()).hasSize(600);
            assertThat(input.path("query").asString()).isEqualTo("改成八点半");
            return json.readTree("{\"retrieve\":false,\"query\":\"\"}");
        });
        var history = java.util.stream.IntStream.range(0, 12).mapToObj(i ->
                new com.personalos.knowledge.spi.KnowledgeSearchPort.ConversationMessage("ASSISTANT", "已创建双选会08:00".repeat(100))).toList();
        var result = pipeline.retrieveForConversation("改成八点半", 4, history);
        assertThat(result.guarded()).isFalse();
        assertThat(result.hits()).isEmpty();
        verify(recall, never()).candidates(anyString(), anyInt());
    }
    @Test void knowledgeFollowupUsesStandaloneQueryAndKeepsConflictGuard() {
        when(model.call(anyString(), anyString(), anyLong())).thenReturn(
                json.readTree("{\"retrieve\":true,\"query\":\"试用期住宿报销限额\"}"),
                json.readTree("{\"queries\":[]}"), json.readTree("{\"order\":[1,2]}"),
                json.readTree("""
                    {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"SUPPORTED"}],
                    "conflicts":[{"left":1,"right":2,"leftQuote":"每天五百元","rightQuote":"每天八百元"}],"clarification":""}
                    """));
        var result = pipeline.retrieveForConversation("那试用期呢", 1, List.of(
                new com.personalos.knowledge.spi.KnowledgeSearchPort.ConversationMessage("USER", "住宿报销标准是多少")));
        verify(recall).candidates("试用期住宿报销限额", 40);
        assertThat(result.status()).isEqualTo("CONFLICT");
        assertThat(result.hits()).containsExactly(a, b);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"retrieve\":\"false\",\"query\":\"\"}", "{\"retrieve\":true,\"query\":\"\"}", "{\"retrieve\":false,\"query\":\"任意内容\"}", "{\"retrieve\":false,\"query\":null}"})
    void invalidConversationRouteIsNotAFalseClarification(String output) {
        when(model.call(anyString(), anyString(), anyLong())).thenReturn(json.readTree(output));
        assertThatThrownBy(() -> pipeline.retrieveForConversation("改成八点半", 4, List.of())).isInstanceOf(IllegalArgumentException.class);
        verify(recall, never()).candidates(anyString(), anyInt());
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
    @Test void unrelatedConflictCannotBlockSupportedRedisEvidence() {
        var redis=new KnowledgeHitDto(UUID.randomUUID(),"Redis 缓存","redis.md",0,"商品缓存 TTL 为 720 秒，抖动 0—180 秒，空值缓存 90 秒。",.9);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,b,redis));
        responses("{\"queries\":[]}","{\"order\":[1,2,3]}","""
            {"evidence":[{"index":1,"grade":"IRRELEVANT"},{"index":2,"grade":"IRRELEVANT"},{"index":3,"grade":"SUPPORTED"}],
             "conflicts":[{"left":1,"right":2,"leftQuote":"每天五百元","rightQuote":"每天八百元","reason":"差旅制度金额不同"}],
             "clarification":""}
            """);
        var result=pipeline.retrieve("Redis 商品缓存 TTL 是多少？",1);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.hits()).containsExactly(redis);
        assertThat(result.disagreements()).isEmpty();
    }
    @Test void relevantConflictStillRetainsOpposingEvidenceEvenIfGradedIrrelevant() {
        responses("{\"queries\":[]}","{\"order\":[1,2]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"IRRELEVANT"}],
             "conflicts":[{"left":1,"right":2,"leftQuote":"每天五百元","rightQuote":"每天八百元"}],
             "clarification":""}
            """);
        var result=pipeline.retrieve("住宿报销多少？",1);
        assertThat(result.status()).isEqualTo("CONFLICT");
        assertThat(result.hits()).containsExactly(a,b);
        assertThat(result.guarded()).isTrue();
    }
    @Test void fabricatedConflictQuoteIsRejectedAndGenerationIsGuarded() {
        responses("{\"queries\":[]}","{\"order\":[1,2]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"SUPPORTED"}],
             "conflicts":[{"left":1,"right":2,"leftQuote":"不存在的原文","rightQuote":"每天八百元"}],"clarification":""}
            """);
        var result=pipeline.retrieve("问题",4);assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.disagreements()).isEmpty();assertThat(result.guarded()).isTrue();
    }
    @Test void rewriteCanRecoverWhenOriginalQueryReturnsNothing() {
        when(recall.hasContent()).thenReturn(true);
        when(recall.candidates("原问题",40)).thenReturn(List.of());
        responses("{\"queries\":[\"住宿限额\"]}","{\"order\":[1,2]}","""
            {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"PARTIAL"}],"conflicts":[],"clarification":""}
            """);
        var result=pipeline.retrieve("原问题",4);assertThat(result.hits()).contains(a,b);assertThat(result.status()).isEqualTo("READY");
    }

    @Test void emptyLibraryDoesNotCallGeneration() {
        when(recall.hasContent()).thenReturn(false);
        assertThat(pipeline.retrieve("问题",4).status()).isEqualTo("INSUFFICIENT");
        verify(recall, never()).candidates(anyString(), anyInt());
        verifyNoInteractions(model);
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
    @Test void partialAnswerRetainsOtherEvidenceAndUsesOriginalConversationQuestion() {
        var redis=new KnowledgeHitDto(UUID.randomUUID(),"缓存规范","redis.md",0,"商品缓存 TTL 为 720 秒。",.9);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,b,redis));
        String original="那两个报销标准呢，顺便说明缓存 TTL";
        when(model.call(anyString(),anyString(),anyLong())).thenAnswer(call->{
            String rules=call.getArgument(0);
            if(rules.startsWith("判断本轮"))return json.readTree("{\"retrieve\":true,\"query\":\"报销标准与商品缓存 TTL\"}");
            if(rules.contains("queries"))return json.readTree("{\"queries\":[]}");
            if(rules.contains("order"))return json.readTree("{\"order\":[1,2,3]}");
            if(rules.startsWith("将 question")) {
                var payload=json.readTree((String)call.getArgument(1));
                assertThat(payload.path("question").asString()).isEqualTo(original);
                assertThat(payload.path("retrievalQueries").toString()).contains("报销标准与商品缓存 TTL");
                assertThat(payload.has("history")).isFalse();
                return json.readTree("""
                    {"parts":[{"topic":"缓存 TTL","status":"SUPPORTED","text":"商品缓存 TTL 为 720 秒。","evidence":[{"index":3,"quote":"商品缓存 TTL 为 720 秒"}]}]}
                    """);
            }
            if(rules.startsWith("独立核对"))return json.readTree("{\"complete\":true,\"conflictsRespected\":true,\"parts\":[{\"index\":1,\"supported\":true}]}");
            return json.readTree("""
                {"evidence":[{"index":1,"grade":"SUPPORTED"},{"index":2,"grade":"SUPPORTED"},{"index":3,"grade":"SUPPORTED"}],
                 "conflicts":[{"left":1,"right":2,"leftQuote":"每天五百元","rightQuote":"每天八百元","reason":"金额不同"}],"clarification":""}
                """);
        });
        var r=pipeline.retrieveForConversation(original,1,List.of());
        assertThat(r.hits()).containsExactly(a,b,redis);
        assertThat(r.guarded()).isTrue();assertThat(r.preparedAnswer()).isNotBlank();
        assertThat(r.guardedAnswer()).contains("可确认的内容","720 秒。 [3]","每天五百元","每天八百元");
        assertThat(r.stages().getLast().status()).isEqualTo("OK");
    }
}
