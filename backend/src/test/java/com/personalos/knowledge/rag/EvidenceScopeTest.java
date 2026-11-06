package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvidenceScopeTest {
    final ObjectMapper json=new ObjectMapper();
    final KnowledgeHitDto a=new KnowledgeHitDto(UUID.randomUUID(),"规范甲","a.md",0,"草稿只在原会话保留，附件切换时保留。",.9);
    final KnowledgeHitDto b=new KnowledgeHitDto(UUID.randomUUID(),"规范乙","b.md",0,"附件切换时清空。",.8);
    final RagResult.Disagreement conflict=new RagResult.Disagreement(a,b,"附件切换时保留","附件切换时清空","附件规则不同");
    @Test void restoredConflictMustBeExplicitlyScopedAndNeverDeletesOriginalEvidence() {
        var model=mock(RagModelGateway.class);var review=new EvidenceReview(model,json);
        var original=new EvidenceReview.Review(List.of(a),List.of(),"");
        var applied=new EvidenceReview.Review(List.of(a,b),List.of(conflict),"");
        when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree("""
            {"conflicts":[{"index":1,"relevant":false,"reason":"本轮仅问草稿，分歧涉及附件"}]}
            """));
        var scoped=review.scopeRestored("草稿如何隔离",original,applied,4000);
        assertThat(scoped.conflicts()).isEmpty();assertThat(scoped.relevant()).containsExactly(a);
        assertThat(applied.conflicts()).containsExactly(conflict);
        when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree("""
            {"conflicts":[{"index":1,"relevant":true,"reason":"本轮询问附件隔离"}]}
            """));
        assertThat(review.scopeRestored("附件如何隔离",original,applied,4000).conflicts()).containsExactly(conflict);
        for(String raw:List.of("{}","{\"conflicts\":[]}","{\"conflicts\":[{\"index\":1,\"relevant\":\"false\",\"reason\":\"无关\"}]}","{\"conflicts\":[{\"index\":2,\"relevant\":false,\"reason\":\"无关\"}]}")) {
            when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree(raw));
            assertThatThrownBy(()->review.scopeRestored("草稿如何隔离",original,applied,4000)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void freshlyReviewedConflictsAreNotSilentlyDismissed() {
        var model=mock(RagModelGateway.class);var review=new EvidenceReview(model,json);
        var original=new EvidenceReview.Review(List.of(a,b),List.of(conflict),"");
        assertThat(review.scopeRestored("附件",original,original,4000)).isSameAs(original);
        verifyNoInteractions(model);
    }
}
