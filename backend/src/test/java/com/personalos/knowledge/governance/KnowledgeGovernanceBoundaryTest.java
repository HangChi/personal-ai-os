package com.personalos.knowledge.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.*;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class KnowledgeGovernanceBoundaryTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final VectorStoreInspector vectors = mock(VectorStoreInspector.class);
    final ObjectMapper json = new ObjectMapper();
    final UUID a = new UUID(0, 1), b = new UUID(0, 2), space = new UUID(0, 3);
    KnowledgeGovernance governance;

    @BeforeEach void setup() {
        governance = spy(new KnowledgeGovernance(db, json, vectors));
        doReturn(Map.of(a, source(1, "ACTIVE"), b, source(2, "ACTIVE"))).when(governance).sources(anyCollection());
    }

    SourceInfo source(long revision, String state) {
        return new SourceInfo(space, "合成知识库", "", "", null, null, "", state, revision, "CHECKED_CANDIDATES");
    }
    KnowledgeHitDto hit(UUID id, SourceInfo source) {
        return new KnowledgeHitDto(id, "合成制度", "fixture.md", 0, "甲报价和乙报价", .8).withSource(source);
    }
    KnowledgeHitDto left() { return hit(a, source(1, "ACTIVE")); }
    KnowledgeHitDto right() { return hit(b, source(2, "ACTIVE")); }
    RagResult.Disagreement conflict(KnowledgeHitDto l, KnowledgeHitDto r) {
        return new RagResult.Disagreement(l, r, "甲报价", "乙报价", "报价不同");
    }
    Map<String, Object> stored() {
        return new HashMap<>(Map.of("left_id", a, "right_id", b, "left_revision", 1L, "right_revision", 2L,
                "left_chunk", 0, "right_chunk", 0, "left_quote", "甲报价", "right_quote", "乙报价", "reason", "报价不同"));
    }

    @Test void spaceNamesAreValidatedBeforeWritesAndDuplicatesAreBusinessErrors() {
        for (String name : Arrays.asList(null, "", " ", "字".repeat(121))) {
            assertThatThrownBy(() -> governance.createSpace(name)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(db);
        assertThat(governance.createSpace("  合成知识库  ").name()).isEqualTo("合成知识库");
        doThrow(new DuplicateKeyException("synthetic duplicate")).when(db).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> governance.createSpace("合成知识库")).hasMessageContaining("已存在");
    }

    @Test void metadataRejectsMissingLifecycleSpaceAndReversedDates() {
        for (var m : Arrays.asList(null,
                new KnowledgeGovernance.Metadata(null, "", "", null, null, "", "ACTIVE"),
                new KnowledgeGovernance.Metadata(space, "", "", null, null, "", null),
                new KnowledgeGovernance.Metadata(space, "", "", null, null, "", "UNKNOWN"),
                new KnowledgeGovernance.Metadata(space, "", "", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 6), "", "ACTIVE"))) {
            assertThatThrownBy(() -> governance.update(a, m)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(db);
        when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> governance.update(a, new KnowledgeGovernance.Metadata(space, "", "", null, null, "", "ACTIVE"))).hasMessageContaining("知识库不存在");
        when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        for (int index = 0; index < 3; index++) {
            String version = index == 0 ? "x".repeat(121) : "";
            String scope = index == 1 ? "x".repeat(501) : "";
            String authority = index == 2 ? "x".repeat(301) : "";
            var invalid = new KnowledgeGovernance.Metadata(space, version, scope, null, null, authority, "ACTIVE");
            assertThatThrownBy(() -> governance.update(a, invalid)).hasMessageContaining("长度");
        }
        var valid = new KnowledgeGovernance.Metadata(space, null, " 范围 ", null, LocalDate.of(2027, 1, 1), null, "ACTIVE");
        assertThatThrownBy(() -> governance.update(a, valid)).hasMessageContaining("文档不存在");
        when(db.update(startsWith("UPDATE knowledge_source"), any(Object[].class))).thenReturn(1);
        assertThat(governance.update(a, valid).revision()).isEqualTo(1);
    }

    @Test void resolutionRequiresOpenConflictReasonAndOneOfTheActualSources() {
        for (var resolution : Arrays.asList(null, new KnowledgeGovernance.Resolution(null, null, "原因"),
                new KnowledgeGovernance.Resolution("MODEL_CHOICE", null, "原因"),
                new KnowledgeGovernance.Resolution("KEEP_BOTH", null, null),
                new KnowledgeGovernance.Resolution("KEEP_BOTH", null, " "),
                new KnowledgeGovernance.Resolution("KEEP_BOTH", null, "x".repeat(1001)))) {
            assertThatThrownBy(() -> governance.resolve(a, resolution)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(db);
        assertThatThrownBy(() -> governance.resolve(a, new KnowledgeGovernance.Resolution("KEEP_BOTH", null, "合成原因"))).hasMessageContaining("不存在");
        when(db.queryForList(startsWith("SELECT left_id,right_id,status"), any(Object[].class))).thenReturn(List.of(Map.of("left_id", a, "right_id", b, "status", "STALE")));
        assertThatThrownBy(() -> governance.resolve(a, new KnowledgeGovernance.Resolution("KEEP_BOTH", null, "原因"))).hasMessageContaining("变化");
        when(db.queryForList(startsWith("SELECT left_id,right_id,status"), any(Object[].class))).thenReturn(List.of(Map.of("left_id", a, "right_id", b, "status", "OPEN")));
        assertThatThrownBy(() -> governance.resolve(a, new KnowledgeGovernance.Resolution("PREFER_SOURCE", space, "原因"))).hasMessageContaining("双方");
        for (var resolution : List.of(new KnowledgeGovernance.Resolution("PREFER_SOURCE", a, "选择甲"),
                new KnowledgeGovernance.Resolution("PREFER_SOURCE", b, "选择乙"), new KnowledgeGovernance.Resolution("NOT_CONFLICT", null, "适用条件不同"))) {
            governance.resolve(a, resolution);
        }
        verify(db, times(3)).update(startsWith("INSERT INTO knowledge_conflict_audit"), any(Object[].class));
    }

    @Test void recordCanonicalizesDirectionAndFencesAgainstConcurrentSourceRevisions() {
        var l = left(); var r = right();
        governance.record(List.of(conflict(l, r), new RagResult.Disagreement(r, l, "乙报价", "甲报价", "报价不同")));
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(db, times(2)).update(startsWith("INSERT INTO knowledge_conflict("), args.capture());
        assertThat(args.getAllValues().get(0)).isEqualTo(args.getAllValues().get(1));
        clearInvocations(db);
        governance.record(List.of(conflict(hit(a, null), r), conflict(l, hit(b, null)), conflict(l, l)));
        verifyNoInteractions(db);
        for (var current : List.of(Map.of(a, source(1, "ACTIVE")),
                Map.of(a, source(3, "ACTIVE"), b, source(2, "ACTIVE")),
                Map.of(a, source(1, "ACTIVE"), b, source(3, "ACTIVE")))) {
            doReturn(current).when(governance).sources(anyCollection());
            governance.record(List.of(conflict(l, r)));
        }
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void enrichDropsMissingAndSupersededDocumentsAndExpansionSkipsMissingChunks() {
        doReturn(new HashMap<>(Map.of(a, source(1, "SUPERSEDED")))).when(governance).sources(anyCollection());
        assertThat(governance.enrich(List.of(left(), right(), hit(null, null)))).isEmpty();
        assertThat(governance.expandConflictingSources(List.of())).isEmpty();
        when(db.queryForList(startsWith("SELECT left_id,right_id,left_chunk"), any(Object[].class))).thenReturn(List.of(stored()));
        assertThat(governance.expandConflictingSources(List.of(left()))).containsExactly(left());
        verify(vectors).documentChunk(b, 0);
        verify(vectors, never()).documentChunk(a, 0);
    }

    @Test void restoredFindingsRequireMatchingAnchorsRevisionsAndExactQuotes() {
        var l = left(); var r = right(); var original = conflict(l, r);
        when(db.queryForList(startsWith("SELECT * FROM knowledge_conflict WHERE status"))).thenReturn(List.of(stored()));
        for (var existing : List.of(original, new RagResult.Disagreement(r, l, "乙报价", "甲报价", "反向"))) {
            var reviewed = governance.applyDecisions(new EvidenceReview.Review(List.of(l), List.of(existing), ""), List.of(l, r), new ArrayList<>());
            assertThat(reviewed.conflicts()).hasSize(1);
            assertThat(reviewed.relevant()).contains(l, r);
        }
        var invalidPools = List.of(List.of(l), List.of(r), List.of(hit(a, null), r), List.of(l, hit(b, null)),
                List.of(hit(a, source(9, "ACTIVE")), r), List.of(l, hit(b, source(9, "ACTIVE"))),
                List.of(new KnowledgeHitDto(a, "甲", "a.md", 0, "不含甲原文", .8).withSource(l.source()), r),
                List.of(l, new KnowledgeHitDto(b, "乙", "b.md", 0, "不含乙原文", .8).withSource(r.source())));
        for (var pool : invalidPools) {
            assertThat(governance.applyDecisions(new EvidenceReview.Review(List.of(l), List.of(), ""), pool, new ArrayList<>()).conflicts()).isEmpty();
        }
        assertThat(governance.applyDecisions(new EvidenceReview.Review(List.of(), List.of(), ""), List.of(l, r), new ArrayList<>()).conflicts()).isEmpty();
    }

    @Test void humanDecisionsSelectEitherSourceButKeepBothDoesNotResolveTheFinding() {
        var l = left(); var r = right(); var c = conflict(l, r);
        for (String decision : List.of("KEEP_BOTH", "NOT_CONFLICT", "PREFER_SOURCE")) {
            for (UUID winner : List.of(a, b)) {
                when(db.queryForList(startsWith("SELECT resolution,winner_id"), any(Object[].class))).thenReturn(List.of(Map.of("resolution", decision, "winner_id", winner, "resolution_reason", "合成人工依据")));
                var warnings = new ArrayList<String>();
                var reviewed = governance.applyDecisions(new EvidenceReview.Review(List.of(l, r), List.of(c), "待确认"), List.of(l, r), warnings);
                if (decision.equals("KEEP_BOTH")) {
                    assertThat(reviewed.conflicts()).containsExactly(c);
                    assertThat(reviewed.relevant()).containsExactly(l, r);
                    assertThat(warnings).isEmpty();
                } else {
                    assertThat(reviewed.conflicts()).isEmpty();
                    assertThat(warnings).containsExactly("Human review: 合成人工依据");
                    if (decision.equals("PREFER_SOURCE")) assertThat(reviewed.relevant()).containsExactly(winner.equals(a) ? l : r);
                }
                assertThat(reviewed.clarification()).isEqualTo("待确认");
            }
        }
        for (var unknown : List.of(conflict(hit(a, null), r), conflict(l, hit(b, null)))) {
            assertThat(governance.applyDecisions(new EvidenceReview.Review(List.of(l), List.of(unknown), ""), List.of(l, r), new ArrayList<>()).conflicts()).containsExactly(unknown);
        }
    }

 @Test void conflictDeduplicationRequiresEveryDocumentAndQuoteToMatch(){
  var l=left();var r=right();var original=conflict(l,r);
  for(var candidate:List.of(new RagResult.Disagreement(l,r,"changed","乙报价","reason"),new RagResult.Disagreement(l,r,"甲报价","changed","reason"),new RagResult.Disagreement(r,l,"changed","甲报价","reason"),new RagResult.Disagreement(r,l,"乙报价","changed","reason"),new RagResult.Disagreement(l,l,"甲报价","乙报价","reason"))){
   assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(KnowledgeGovernance.class,"sameEvidence",candidate,l,r,"甲报价","乙报价")).isFalse();
  }
  assertThat(new KnowledgeGovernance(db,json,vectors).sources(List.of())).isEmpty();
  var row=stored();when(db.queryForList(startsWith("SELECT left_id,right_id,left_chunk"),any(Object[].class))).thenReturn(List.of(row));
  assertThat(governance.expandConflictingSources(List.of(l,r))).containsExactly(l,r);
 }
}
