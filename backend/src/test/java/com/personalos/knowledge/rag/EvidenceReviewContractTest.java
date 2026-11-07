package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.governance.SourceInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Java 与 Go 共用模型输出及预期，防止证据校验协议漂移。 */
class EvidenceReviewContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Stream<Arguments> reviewCases() throws Exception { return cases("evidence-review.json"); }
    static Stream<Arguments> scopeCases() throws Exception { return cases("restored-conflict-scope.json"); }

    private static Stream<Arguments> cases(String file) throws Exception {
        var fixture = JSON.readTree(Files.readString(Path.of("../testdata/agent-rag", file)));
        var cases = new ArrayList<Arguments>();
        for (var test : fixture.path("cases")) cases.add(Arguments.of(test.path("name").asString(), test, fixture));
        return cases.stream();
    }

    private static List<KnowledgeHitDto> hits() {
        return List.of(hit(1, "每周工作五天，每天八小时。"), hit(2, "每周工作四天，每天六小时。"),
                hit(3, "节假日另行安排，周末正常休息。"));
    }

    private static KnowledgeHitDto hit(int id, String content) {
        return new KnowledgeHitDto(new UUID(0, id), "制度" + id, id + ".md", 0, content, .9);
    }

    private static LocalDate date(JsonNode node) { return node.isTextual() ? LocalDate.parse(node.asString()) : null; }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reviewCases")
    void sharedReviewGradesGroundingAndEffectivePeriods(String name, JsonNode test, JsonNode fixture) {
        var gateway = mock(RagModelGateway.class);
        var review = new EvidenceReview(gateway, JSON);
        var evidence = new ArrayList<KnowledgeHitDto>();
        for (var item : fixture.path("hits")) evidence.add(hit(evidence.size() + 1, item.path("content").asString()));
        if (test.has("sources")) {
            for (int i = 0; i < evidence.size(); i++) {
                var source = test.path("sources").get(i);
                if (!source.isNull()) evidence.set(i, evidence.get(i).withSource(new SourceInfo(null, null, null, null,
                        date(source.path("effectiveFrom")), date(source.path("effectiveUntil")), null, null, 0, null)));
            }
        }
        when(gateway.call(anyString(), anyString(), eq(4000L))).thenReturn(test.path("response"));
        if (!test.path("valid").asBoolean()) {
            assertThatThrownBy(() -> review.review("工作制度", evidence, 4000)).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            var result = review.review("工作制度", evidence, 4000);
            assertThat(result.relevant()).as(name).containsExactlyElementsOf(selected(evidence, test.path("expectedRelevant")));
            assertThat(result.conflicts()).hasSize(test.path("expectedConflicts").asInt());
            assertThat(result.clarification()).isEqualTo(test.path("expectedClarification").asString());
            for (var conflict : result.conflicts()) {
                assertThat(conflict.left()).isEqualTo(evidence.get(0));
                assertThat(conflict.right()).isEqualTo(evidence.get(1));
                assertThat(conflict.leftQuote()).isEqualTo("每周工作五天");
                assertThat(conflict.rightQuote()).isEqualTo("每周工作四天");
                assertThat(conflict.reason()).isEqualTo("工作天数不同");
            }
        }
        verify(gateway).call(anyString(), anyString(), eq(4000L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopeCases")
    void sharedScopeRequiresCompleteTypedRowsAndPreservesOriginalEvidence(String name, JsonNode test, JsonNode fixture) {
        var gateway = mock(RagModelGateway.class);
        var review = new EvidenceReview(gateway, JSON);
        var h = hits();
        var conflicts = List.of(conflict(h.get(0), h.get(1)), conflict(h.get(0), h.get(2)));
        var original = new EvidenceReview.Review(h.subList(0, 1), List.of(), "");
        var applied = new EvidenceReview.Review(h, conflicts, "请指定制度版本");
        when(gateway.call(anyString(), anyString(), eq(4000L))).thenReturn(test.path("response"));
        if (!test.path("valid").asBoolean()) {
            assertThatThrownBy(() -> review.scopeRestored("工作制度", original, applied, 4000)).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            var scoped = review.scopeRestored("工作制度", original, applied, 4000);
            assertThat(scoped.conflicts()).as(name).containsExactlyElementsOf(selected(conflicts, test.path("expectedConflicts")));
            assertThat(scoped.relevant()).containsExactlyElementsOf(selected(h, test.path("expectedRelevant")));
            assertThat(scoped.clarification()).isEqualTo(applied.clarification());
        }
        assertThat(applied.relevant()).containsExactlyElementsOf(h);
        assertThat(applied.conflicts()).containsExactlyElementsOf(conflicts);
        verify(gateway).call(anyString(), anyString(), eq(4000L));
    }

    @Test
    void scopeNeverDismissesFreshConflictsAndRejectsExcessRestoredConflictsBeforeCallingModel() {
        var gateway = mock(RagModelGateway.class);
        var review = new EvidenceReview(gateway, JSON);
        var h = hits();
        var fresh = conflict(h.get(0), h.get(1));
        var restored = conflict(h.get(0), h.get(2));
        var original = new EvidenceReview.Review(h.subList(0, 2), List.of(fresh), "");
        var applied = new EvidenceReview.Review(h, List.of(fresh, restored), "");
        var response = java.util.Map.of("conflicts", List.of(java.util.Map.of("index", 1, "relevant", false, "reason", "与当前问题无关")));
        when(gateway.call(anyString(), anyString(), eq(4000L))).thenReturn(JSON.valueToTree(response));
        var scoped = review.scopeRestored("工作制度", original, applied, 4000);
        assertThat(scoped.conflicts()).containsExactly(fresh);
        assertThat(scoped.relevant()).containsExactlyElementsOf(original.relevant());
        clearInvocations(gateway);
        var excess = new EvidenceReview.Review(h, java.util.Collections.nCopies(17, restored), "");
        assertThatThrownBy(() -> review.scopeRestored("工作制度", original, excess, 4000))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void modelFailuresPropagateSoPipelineCanApplyItsUnverifiedFallback() {
        var gateway = mock(RagModelGateway.class);
        var failure = new IllegalStateException("synthetic verifier failure");
        when(gateway.call(anyString(), anyString(), eq(4000L))).thenThrow(failure);
        var review = new EvidenceReview(gateway, JSON);
        assertThatThrownBy(() -> review.review("工作制度", hits(), 4000)).isSameAs(failure);
        var original = new EvidenceReview.Review(hits().subList(0, 1), List.of(), "");
        var applied = new EvidenceReview.Review(hits(), List.of(conflict(hits().get(0), hits().get(1))), "");
        assertThatThrownBy(() -> review.scopeRestored("工作制度", original, applied, 4000)).isSameAs(failure);
    }

    private static RagResult.Disagreement conflict(KnowledgeHitDto left, KnowledgeHitDto right) {
        return new RagResult.Disagreement(left, right, left.content().substring(0, 6), right.content().substring(0, 6), "分歧");
    }

    private static <T> List<T> selected(List<T> all, JsonNode indices) {
        var selected = new ArrayList<T>();
        for (var index : indices) selected.add(all.get(index.asInt() - 1));
        return selected;
    }
}
