package com.personalos.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.agent.plan.entity.AgentPlanEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.support.ChatResponseInspection;
import com.personalos.common.ai.ContextProtectionProperties;
import com.personalos.common.auth.AuthProperties;
import com.personalos.memory.entity.*;
import com.personalos.memory.service.*;
import com.personalos.support.StaticObjectProvider;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class BoundaryStateContractsTest {
    @Test void summaryInvalidationPreservesUnaffectedHistoryAndHandlesLegacyNull() {
        var s = new ChatSessionEntity("c", "title", Instant.EPOCH, Instant.EPOCH);
        s.applySummary("old", 4);
        s.invalidateSummaryFrom(5);
        assertThat(s.getContextSummary()).isEqualTo("old");
        s.invalidateSummaryFrom(4);
        assertThat(s.getContextSummary()).isNull();
        assertThat(s.getSummaryUptoSeq()).isZero();
        ReflectionTestUtils.setField(s, "summaryUptoSeq", null);
        s.invalidateSummaryFrom(1);
        assertThat(s.getSummaryUptoSeq()).isZero();
    }
    @Test void planTerminalTransitionsAreIdempotent() {
        var p = new AgentPlanEntity("c", "goal", Instant.EPOCH);
        assertThat(p.isActive()).isTrue();
        p.complete(Instant.ofEpochSecond(1));
        p.complete(Instant.ofEpochSecond(2));
        assertThat(p.isActive()).isFalse();
        assertThat(p.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1));
        p.abort(Instant.ofEpochSecond(3));
        p.abort(Instant.ofEpochSecond(4));
        assertThat(p.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(3));
    }
    @Test void memoryLegacySourceAndEmbeddingMetadataRemainConsistent() {
        var m = new MemoryEntity("memory", Instant.EPOCH, null);
        assertThat(m.getSource()).isEqualTo(MemorySource.MANUAL);
        assertThat(m.isActive()).isTrue();
        m.setSupersededBy(UUID.randomUUID());
        assertThat(m.isActive()).isFalse();
        for (String model : Arrays.asList(null, " ", " model ")) {
            m.markEmbedded(model, Instant.EPOCH);
            assertThat(m.getEmbeddingModel()).isEqualTo(model == null || model.isBlank() ? null : "model");
        }
    }
    @Test void configurationDefaultsAndInvalidLockoutsAreExplicit() {
        var c = new ContextProtectionProperties(0, -1, 0, 5);
        assertThat(c.outputReserveTokens()).isEqualTo(4096);
        assertThat(c.safetyMarginTokens()).isEqualTo(512);
        assertThat(c.imageReserveTokens()).isEqualTo(4096);
        assertThat(c.maxRetries()).isEqualTo(2);
        for (String user : Arrays.asList(null, " ")) {
            assertThat(new AuthProperties(false, user, "", 1, Duration.ofSeconds(1)).username()).isEqualTo("admin");
        }
        assertThatThrownBy(() -> new AuthProperties(false, "x", "", 0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        for (Duration d : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new AuthProperties(false, "x", "", 1, d)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void partialModelResponseDoesNotInventTextOrUsage() {
        var response = mock(ChatResponse.class);
        assertThat(ChatResponseInspection.textOf(response)).isEmpty();
        var generation = mock(Generation.class);
        when(response.getResult()).thenReturn(generation);
        assertThat(ChatResponseInspection.textOf(response)).isEmpty();
        assertThat(ChatResponseInspection.usageOf(response)).isNull();
        var metadata = mock(ChatResponseMetadata.class);
        when(response.getMetadata()).thenReturn(metadata);
        assertThat(ChatResponseInspection.usageOf(response)).isNull();
        var usage = mock(Usage.class);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(null);
        when(usage.getCompletionTokens()).thenReturn(null);
        assertThat(ChatResponseInspection.usageOf(response)).isNull();
        when(usage.getPromptTokens()).thenReturn(3);
        assertThat(ChatResponseInspection.usageOf(response).promptTokens()).isEqualTo(3);
        when(usage.getPromptTokens()).thenReturn(0);
        when(usage.getCompletionTokens()).thenReturn(2);
        assertThat(ChatResponseInspection.usageOf(response).completionTokens()).isEqualTo(2);
    }
    @Test void embeddingOptionalCapabilityAndEmptyVectorsAreSafe() {
        var absent = new MemoryEmbeddingService(new StaticObjectProvider<>(List.of()), null, " ");
        assertThat(absent.embed("text")).isEmpty();
        assertThat(absent.modelTag()).isEqualTo("unknown");
        var model = mock(EmbeddingModel.class);
        var service = new MemoryEmbeddingService(new StaticObjectProvider<>(List.of(model)), " ", " local ");
        when(model.embed("text")).thenReturn(null, new float[0]);
        assertThat(service.embed("text")).isEmpty();
        assertThat(service.embed("text")).isEmpty();
        assertThat(service.modelTag()).isEqualTo("local");
    }
    @Test void invalidEmbeddingQueriesAvoidDatabaseAndDeletedWritesReturnFalse() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new MemoryEmbeddingStore(jdbc);
        var id = UUID.randomUUID();
        assertThat(store.save(null, new float[]{1}, "m")).isFalse();
        assertThat(store.save(id, null, "m")).isFalse();
        assertThat(store.save(id, new float[0], "m")).isFalse();
        for (float[] vector : Arrays.asList(null, new float[0], new float[]{1})) {
            for (String model : Arrays.asList(null, " ")) {
                assertThat(store.findSimilar(vector, model, .9, id)).isEmpty();
                assertThat(store.similarityTo(vector, model, 5)).isEmpty();
            }
        }
        verifyNoInteractions(jdbc);
        assertThat(store.save(id, new float[]{1}, "m")).isFalse();
    }
}
