package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.knowledge.spi.KnowledgeSearchPort.ConversationMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
class RagPipelineBoundaryTest {
    final ObjectMapper json=new ObjectMapper();
    final KnowledgeRetrievalService recall=mock(KnowledgeRetrievalService.class);
    final RagModelGateway model=mock(RagModelGateway.class);
    final EvidenceReview review=mock(EvidenceReview.class);
    final KnowledgeProperties properties=new KnowledgeProperties(1024,4,.3,50000,List.of("md"),10,1200,0,true,60,1);
    final KnowledgeHitDto a=new KnowledgeHitDto(new UUID(0,1),"甲","a.md",0,"合成证据甲",.8);
    final KnowledgeHitDto b=new KnowledgeHitDto(new UUID(0,2),"乙","b.md",0,"合成证据乙",.7);
    RagRetrievalPipeline pipeline;
    @BeforeEach void setup(){
        pipeline=new RagRetrievalPipeline(recall,model,review,properties,json,new SimpleMeterRegistry(),true,20000);
        when(recall.hasContent()).thenReturn(true);when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,b));
        when(review.review(anyString(),anyList(),anyLong())).thenAnswer(c -> new EvidenceReview.Review(c.getArgument(1),List.of(),""));
        when(model.call(anyString(),anyString(),anyLong())).thenAnswer(c -> json.readTree(((String)c.getArgument(0)).contains("queries")?"{~queries~:[]}".replace('~','"'):"{~order~:[1,2]}".replace('~','"')));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" "})
    void invalidQueriesFailBeforeRecall(String query){
        assertThatThrownBy(() -> pipeline.retrieve(query,4)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pipeline.retrieveForConversation(query,4,List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(recall,model);
    }
    @Test void oversizedQueriesAndDisabledModeDoNotSpendModelBudget(){
        assertThatThrownBy(() -> pipeline.retrieve("x".repeat(2001),4)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pipeline.retrieveForConversation("x".repeat(4001),4,List.of())).isInstanceOf(IllegalArgumentException.class);
        pipeline=new RagRetrievalPipeline(recall,model,review,properties,json,new SimpleMeterRegistry(),false,20000);
        when(recall.search("问题",4)).thenReturn(List.of(a));
        assertThat(pipeline.retrieveForConversation("问题",4,List.of()).hits()).containsExactly(a);verifyNoInteractions(model,review);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{~queries~:null}","{~queries~:[~ ~]}","{~queries~:[~a~,~b~,~c~]}"})
    void invalidRewritesRetainOriginalRecall(String response){
        when(model.call(contains("queries"),anyString(),anyLong())).thenReturn(json.readTree(response.replace('~','"')));
        var r=pipeline.retrieve("问题",2);assertThat(r.status()).isEqualTo("READY");assertThat(r.queries()).containsExactly("问题");
        assertThat(r.warnings()).anyMatch(w -> w.contains("改写"));assertThat(r.hits()).containsExactly(a,b);
    }
    @Test void numericRewriteUsesExistingJacksonTextConversion() {
        when(model.call(contains("queries"),anyString(),anyLong())).thenReturn(json.readTree("{~queries~:[1]}".replace('~','"')));
        var result=pipeline.retrieve("问题",2);
        assertThat(result.queries()).containsExactly("问题","1");
        verify(recall).candidates("1",40);
    }
    @Test void oversizedRewriteFallsBackAndInvalidDocumentIsNotCited() {
        var invalid=new KnowledgeHitDto(null,"失效","missing.md",0,"失效证据",.99);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(invalid,a,b));
        when(model.call(contains("queries"),anyString(),anyLong()))
                .thenReturn(json.valueToTree(Map.of("queries",List.of("x".repeat(501)))));
        var result=pipeline.retrieve("问题",2);
        assertThat(result.queries()).containsExactly("问题");
        assertThat(result.warnings()).contains("查询改写不可用，保留原问题召回");
        assertThat(result.hits()).containsExactly(a,b);
    }
    @Test void emptyEvidenceReviewDoesNotProduceAnAnswer() {
        when(review.review(anyString(),anyList(),anyLong()))
                .thenReturn(new EvidenceReview.Review(List.of(),List.of(),""));
        assertThat(pipeline.retrieve("问题",2).status()).isEqualTo("INSUFFICIENT");
        when(review.review(anyString(),anyList(),anyLong()))
                .thenReturn(new EvidenceReview.Review(List.of(),List.of(),"请明确时间范围"));
        var result=pipeline.retrieve("问题",2);
        assertThat(result.status()).isEqualTo("CLARIFY");
        assertThat(result.hits()).isEmpty();
        assertThat(result.clarification()).isEqualTo("请明确时间范围");
    }
    @ParameterizedTest @ValueSource(strings={"{}","{~order~:[]}","{~order~:[1]}","{~order~:[1,1]}","{~order~:[0,2]}","{~order~:[1,3]}"})
    void malformedReranksRetainFusionOrder(String response){
        when(model.call(contains("order"),anyString(),anyLong())).thenReturn(json.readTree(response.replace('~','"')));
        var r=pipeline.retrieve("问题",2);assertThat(r.hits()).containsExactly(a,b);assertThat(r.warnings()).anyMatch(w -> w.contains("重排"));
    }
    @Test void partialRewriteRecallFailureAndEmptyCandidatesAreExplicit(){
        when(model.call(contains("queries"),anyString(),anyLong())).thenReturn(json.readTree("{~queries~:[~ 问题 ~,~改写~]}".replace('~','"')));
        when(recall.candidates("改写",40)).thenThrow(new IllegalStateException("synthetic recall failure"));
        var r=pipeline.retrieve("问题",2);assertThat(r.queries()).containsExactly("问题","改写");assertThat(r.warnings()).contains("部分改写召回失败");
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of());
        assertThat(pipeline.retrieve("问题",2).status()).isEqualTo("INSUFFICIENT");
    }
    @Test void crossEncoderScoresAndPerDocumentDiversitySurviveSelection(){
        var same=new KnowledgeHitDto(a.documentId(),"甲","a.md",1,"甲第二块",.9);var third=new KnowledgeHitDto(a.documentId(),"甲","a.md",2,"甲第三块",.9);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of(a,same,third,b));
        CrossEncoderReranker encoder=mock(CrossEncoderReranker.class);ObjectProvider<CrossEncoderReranker> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(encoder);pipeline.configureReranker(provider);
        when(encoder.rank(anyString(),anyList(),anyLong())).thenReturn(new CrossEncoderReranker.Ranking(List.of(a,same,third,b),Map.of(a.documentId()+":0",.95)));
        var r=pipeline.retrieve("问题",20);assertThat(r.hits()).containsExactly(a,b,same);assertThat(r.rankingScores().get(a.documentId()+":0").rerank()).isEqualTo(.95);
        when(encoder.rank(anyString(),anyList(),anyLong())).thenThrow(new IllegalStateException("synthetic reranker failure"));
        assertThat(pipeline.retrieve("问题",1).warnings()).contains("重排不可用，采用 RRF 排名");
    }
    @Test void emptyConversationLibraryAndRoleFilteringDoNotExposeSystemHistory(){
        when(recall.hasContent()).thenReturn(false);assertThat(pipeline.retrieveForConversation("问题",4,List.of()).hits()).isEmpty();verifyNoInteractions(model);
        when(recall.hasContent()).thenReturn(true);
        when(model.call(startsWith("判断本轮"),anyString(),anyLong())).thenAnswer(c -> {
            var payload=json.readTree((String)c.getArgument(1));assertThat(payload.path("history").size()).isEqualTo(1);
            return json.readTree("{~retrieve~:false,~query~:~~}".replace('~','"'));
        });
        pipeline.retrieveForConversation("问题",4,List.of(new ConversationMessage("SYSTEM","秘密"),new ConversationMessage("TOOL","数据"),new ConversationMessage("USER","问题")));
    }
    @Test void conversationRouteRejectsWrongShapeAndOverlongStandaloneQuery(){
        for(String output:List.of("[]","{~retrieve~:false,~query~:~~,~extra~:true}","{~retrieve~:true,~query~:~"+"x".repeat(2001)+"~}")){
            when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree(output.replace('~','"')));
            assertThatThrownBy(() -> pipeline.retrieveForConversation("问题",4,List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
