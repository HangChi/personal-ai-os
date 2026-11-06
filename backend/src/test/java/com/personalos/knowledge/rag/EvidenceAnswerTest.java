package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvidenceAnswerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void sharedJavaGoContractRejectsUngroundedPartsAndRendersVerifiedParts() throws Exception {
        var fixture = json.readTree(Files.readString(Path.of("../specs/039-evidence-aware-answers/contracts/answer-cases.json")));
        List<KnowledgeHitDto> hits = json.convertValue(fixture.path("hits"), json.getTypeFactory().constructCollectionType(List.class, KnowledgeHitDto.class));
        List<RagResult.Disagreement> conflicts = json.convertValue(fixture.path("disagreements"), json.getTypeFactory().constructCollectionType(List.class, RagResult.Disagreement.class));
        for (var test : fixture.path("cases")) {
            var gateway = mock(RagModelGateway.class);
            when(gateway.call(anyString(), anyString(), anyLong())).thenReturn(test.path("plan"), test.path("verification"));
            var result = new RagResult(hits, List.of("独立检索问题"), List.of(), List.of(), "CONFLICT", conflicts, "");
            String prepared = new EvidenceAnswer(gateway, json).prepare(fixture.path("question").asString(), result, 8000);
            assertThat(!prepared.isBlank()).as(test.path("name").asString()).isEqualTo(test.path("prepared").asBoolean());
            var completed = result.withPreparedAnswer(prepared);
            assertThat(completed.guardedAnswer()).as(test.path("name").asString()).isEqualTo(test.path("expectedAnswer").asString());
            assertThat(json.writeValueAsString(completed)).doesNotContain("preparedAnswer");
            assertThat(completed.guarded()).isTrue();
            if (!prepared.isBlank()) {
                assertThat(completed.answerWithIndexes(List.of(7, 9, 12))).contains("[7]", "[9]", "[12]").doesNotContain("[1]", "[2]", "[3]");
                var search=mock(com.personalos.knowledge.spi.KnowledgeSearchPort.class);
                var provider=mock(com.personalos.common.ai.AiChatClientProvider.class);
                when(search.retrieve(anyString(),any())).thenReturn(completed);
                var response=new com.personalos.knowledge.service.KnowledgeQaService(search,provider).ask("问题",4);
                assertThat(response.answer()).isEqualTo(completed.guardedAnswer());
                assertThat(response.answered()).isFalse();assertThat(response.citations()).hasSize(3);
                verifyNoInteractions(provider);
            }
        }
    }

    @Test void exhaustedBudgetUnverifiedAndModelFailureKeepProtectiveFallback() {
        var gateway = mock(RagModelGateway.class);
        var answer = new EvidenceAnswer(gateway, json);
        var r = new RagResult(List.of(), List.of(), List.of(), List.of(), "UNVERIFIED", List.of(), "");
        assertThat(answer.prepare("问题", r, 8000)).isEmpty();
        assertThat(answer.prepare("问题", r, 0)).isEmpty();
        verifyNoInteractions(gateway);
        assertThat(r.guardedAnswer()).contains("证据核对未完成").doesNotContain("制度版本", "来源分歧");
        var hit = new KnowledgeHitDto(java.util.UUID.randomUUID(), "资料", "a.md", 0, "有依据的内容", .9);
        var conflict = new RagResult(List.of(hit), List.of(), List.of(), List.of(), "CONFLICT", List.of(), "");
        assertThat(answer.prepare("问题", conflict, 0)).isEmpty();
        verifyNoInteractions(gateway);
        when(gateway.call(anyString(), anyString(), anyLong())).thenThrow(new IllegalStateException("synthetic model failure"));
        assertThat(answer.prepare("问题", conflict, 8000)).isEmpty();
        verify(gateway).call(anyString(), anyString(), anyLong());
    }
}
