package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.dto.KnowledgeHitDto.MatchedBy;
import com.personalos.knowledge.search.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class KnowledgeRetrievalServiceTest {
    final VectorStore vectors = mock(VectorStore.class);
    final VectorStoreInspector inspector = mock(VectorStoreInspector.class);
    final ObjectMapper json = new ObjectMapper();
    final UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);
    KnowledgeRetrievalService service;

    @BeforeEach void setup() {
        service = new KnowledgeRetrievalService(vectors, inspector, properties(true), json);
        doReturn(List.of()).when(vectors).similaritySearch(any(SearchRequest.class));
    }

    KnowledgeProperties properties(boolean hybrid) {
        return new KnowledgeProperties(1024, 4, .3, 50000, List.of("md"), 10, 1200, 0, hybrid, 60, 1);
    }

    Document vector(String id, Object document, Object chunk, double score) {
        var v = mock(Document.class);
        var metadata = new HashMap<String, Object>();
        if (document != null) metadata.put("document_id", document);
        if (chunk != null) metadata.put("chunk_index", chunk);
        when(v.getId()).thenReturn(id);
        when(v.getText()).thenReturn("向量原文 " + id);
        when(v.getMetadata()).thenReturn(metadata);
        when(v.getScore()).thenReturn(score);
        return v;
    }

    String metadata(UUID id, int chunk) {
        return json.writeValueAsString(Map.of("document_id", id.toString(), "chunk_index", chunk, "document_title", "文档", "filename", "fixture.md"));
    }

    VectorStoreInspector.KeywordMatch keyword(String id, UUID document, int chunk, long df, long total) {
        return new VectorStoreInspector.KeywordMatch("redis", new VectorStoreInspector.KeywordChunk(id, "关键词原文", metadata(document, chunk)), df, total);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  "})
    void blankQueriesFailWithoutCallingInfrastructure(String query) {
        assertThatThrownBy(() -> service.search(query, 4)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.candidates(query, 4)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(vectors, inspector);
    }

    @Test void vectorSearchDeduplicatesByBestChunkAndNormalizesMetadata() {
        doReturn(List.of(
                vector("a0", a, 0, .5), vector("a1", a.toString(), "1", .9),
                vector("a2", a, 2, .4), vector("b", b, null, .8),
                vector("bad", "invalid-uuid", "wrong", 1), vector("missing", null, 0, 1))).when(vectors).similaritySearch(any(SearchRequest.class));
        var hits = service.search("  中文问题  ", 2);
        assertThat(hits).extracting(KnowledgeHitDto::documentId).containsExactly(a, b);
        assertThat(hits).extracting(KnowledgeHitDto::chunkIndex).containsExactly(1, -1);
        assertThat(hits.getFirst().documentTitle()).isEmpty();
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors).similaritySearch(request.capture());
        assertThat(request.getValue().getQuery()).isEqualTo("中文问题");
        assertThat(request.getValue().getTopK()).isEqualTo(6);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(.3);
    }

    @Test void candidateLimitPreservesChunksAndUnparseableMetadata() {
        doReturn(List.of(
                vector("a", a, 3L, .9), vector("b", a, "wrong", .8), vector("c", null, null, .7))).when(vectors).similaritySearch(any(SearchRequest.class));
        assertThat(service.candidates("中文", -9)).extracting(KnowledgeHitDto::chunkIndex).containsExactly(3, -1, -1);
        assertThat(service.candidates("中文", 100).getLast().documentId()).isNull();
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors, times(2)).similaritySearch(request.capture());
        assertThat(request.getAllValues()).extracting(SearchRequest::getTopK).containsExactly(1, 60);
    }

    @Test void hybridFusionLabelsBothChannelsAndDeduplicatesDocuments() {
        doReturn(List.of(vector("both", a, 0, .9), vector("vector", b, 0, .8), vector(null, c, 0, .7))).when(vectors).similaritySearch(any(SearchRequest.class));
        when(inspector.searchByKeywords(anyList(), anyInt())).thenReturn(List.of(
                keyword("both", a, 0, 0, 100), keyword("another-a", a, 1, 0, 100), keyword("keyword", c, 0, 0, 100)));
        var candidates = service.candidates("redis watch", 10);
        assertThat(candidates.getFirst().matchedBy()).isEqualTo(MatchedBy.BOTH);
        assertThat(candidates).extracting(KnowledgeHitDto::matchedBy).contains(MatchedBy.VECTOR, MatchedBy.KEYWORD);
        assertThat(candidates.stream().filter(h -> h.matchedBy() == MatchedBy.KEYWORD)).allMatch(h -> h.score() == null);
        assertThat(service.search("redis watch", 10)).extracting(KnowledgeHitDto::documentId).containsExactly(a, b, c);
    }

    @Test void brokenKeywordMetadataSurvivesWithoutInventedDocumentIdentity() {
        var keywords = new ArrayList<VectorStoreInspector.KeywordMatch>();
        int index = 0;
        for (String value : Arrays.asList(null, " ", "not-json", "{~document_id~:~bad~}".replace('~', '"'))) {
            keywords.add(new VectorStoreInspector.KeywordMatch("redis", new VectorStoreInspector.KeywordChunk("bad-" + index++, "证据", value), 1, 1));
        }
        when(inspector.searchByKeywords(anyList(), anyInt())).thenReturn(keywords);
        assertThat(service.search("redis", 20)).hasSize(4).allMatch(h -> h.documentId() == null && h.chunkIndex() == -1 && h.score() == null);
    }

    @Test void commonKeywordsDoNotBeatSemanticEvidenceAndDisabledHybridDoesNotCallKeywordStore() {
        doReturn(List.of(vector("vector", a, 0, .9))).when(vectors).similaritySearch(any(SearchRequest.class));
        when(inspector.searchByKeywords(anyList(), anyInt())).thenReturn(List.of(keyword("keyword", b, 0, 100, 100)));
        assertThat(service.candidates("redis", 4)).extracting(KnowledgeHitDto::documentId).containsExactly(a, b);
        clearInvocations(inspector);
        service = new KnowledgeRetrievalService(vectors, inspector, properties(false), json);
        assertThat(service.search("redis", null)).hasSize(1);
        assertThat(service.candidates("中文查询", 4)).hasSize(1);
        verifyNoInteractions(inspector);
    }

    @Test void chineseTokenFallbackUsesAtMostSixDistinctBigrams() {
        when(inspector.searchByKeywords(anyList(), anyInt())).thenReturn(List.of(new VectorStoreInspector.KeywordMatch("知识", new VectorStoreInspector.KeywordChunk("a", "中文原文", metadata(a, 0)), 1, 20)));
        assertThat(service.candidates("知识检索流程切块规则向量融合排序，知识知识", 4)).singleElement().extracting(KnowledgeHitDto::matchedBy).isEqualTo(MatchedBy.KEYWORD);
        verify(inspector).searchByKeywords(eq(List.of("知识", "检索", "流程", "切块", "规则", "向量")), eq(4));
    }

    @Test void externalSearchHydratesAndFusesBeforeReturningResults() {
        var external = mock(OpenSearchGateway.class);
        var projection = mock(SearchProjection.class);
        var embeddings = mock(EmbeddingModel.class);
        service.configureExternal(provider(external), provider(projection), embeddings);
        var meters = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "recallMetrics", meters);
        when(embeddings.embed("redis")).thenReturn(new float[] {.1f, .2f});
        when(external.search(eq("redis"), any(float[].class), eq(4))).thenReturn(new OpenSearchGateway.Channels(List.of(a, b), List.of(b, c)));
        when(inspector.projectionChunks(anyList())).thenReturn(List.of(
                new VectorStoreInspector.IndexedChunk(a, "a", metadata(a, 0), null),
                new VectorStoreInspector.IndexedChunk(b, "b", metadata(b, 0), null),
                new VectorStoreInspector.IndexedChunk(c, "c", metadata(c, 0), null)));
        var hits = service.candidates("redis", 4);
        assertThat(hits).extracting(KnowledgeHitDto::documentId).containsExactly(b, a, c);
        assertThat(hits).extracting(KnowledgeHitDto::matchedBy).containsExactly(MatchedBy.BOTH, MatchedBy.KEYWORD, MatchedBy.VECTOR);
        assertThat(meters.get("personalos.knowledge.recall").tag("stage", "candidate_hydration").timer().count()).isEqualTo(1);
        verifyNoInteractions(vectors);
        when(external.search(anyString(), any(float[].class), anyInt())).thenThrow(new IllegalStateException("synthetic search outage"));
        service.candidates("redis", 4);
        verify(vectors).similaritySearch(any(SearchRequest.class));
        clearInvocations(vectors, external);
        when(projection.hasPending()).thenReturn(true);
        service.candidates("redis", 4);
        verifyNoInteractions(external);
        verify(vectors).similaritySearch(any(SearchRequest.class));
    }

    @Test void candidateCacheCanReturnSnapshotOrInvokeTheActualFallback() {
        var cache = mock(RedisCandidateCache.class);
        service.configureCandidateCache(provider(cache));
        doReturn(List.of(vector("a", a, 0, .9))).when(vectors).similaritySearch(any(SearchRequest.class));
        when(cache.get(anyString(), anyInt(), any(), any())).thenAnswer(call -> {
            BooleanSupplier ready = call.getArgument(2);
            Function<Boolean, List<KnowledgeHitDto>> load = call.getArgument(3);
            assertThat(ready.getAsBoolean()).isFalse();
            return load.apply(false);
        });
        assertThat(service.candidates("redis", 4)).singleElement().extracting(KnowledgeHitDto::documentId).isEqualTo(a);
        when(inspector.hasContent()).thenReturn(true);
        assertThat(service.hasContent()).isTrue();
    }

    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
