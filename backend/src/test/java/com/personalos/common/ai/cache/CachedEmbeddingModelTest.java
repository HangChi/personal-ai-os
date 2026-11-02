package com.personalos.common.ai.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.*;
import tools.jackson.databind.ObjectMapper;

class CachedEmbeddingModelTest {
    @Test void observesWithoutCachingAndEnabledCacheKeepsBatchOrderAndOptionsIsolated() {
        var delegate = mock(EmbeddingModel.class);
        when(delegate.call(any())).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) results.add(new Embedding(new float[]{i + 1f}, i));
            return new EmbeddingResponse(results);
        });
        var registry = new SimpleMeterRegistry();
        var json = new JsonCodec(new ObjectMapper());
        var observed = new CachedEmbeddingModel(delegate, "model", false, 10, Duration.ofMinutes(1), () -> json, () -> registry);
        observed.embed("重复"); observed.embed("重复");
        verify(delegate, times(2)).call(any());
        assertThat(registry.get("personalos.embedding.cache.requests").tag("result", "observed_repeat").counter().count()).isEqualTo(1);
        clearInvocations(delegate);
        var cached = new CachedEmbeddingModel(delegate, "model", true, 10, Duration.ofMinutes(1), () -> json, () -> registry);
        var first = cached.call(new EmbeddingRequest(List.of("a", "b", "a"), null));
        assertThat(first.getResults()).extracting(e -> e.getOutput()[0]).containsExactly(1f, 2f, 1f);
        first.getResults().getFirst().getOutput()[0] = 100;
        assertThat(cached.embed("a")).containsExactly(1f);
        verify(delegate, times(1)).call(any());
        cached.call(new EmbeddingRequest(List.of("a"), EmbeddingOptions.builder().model("other").build()));
        verify(delegate, times(2)).call(any());
    }
    @Test void neverCachesFailures() {
        var delegate = mock(EmbeddingModel.class);
        when(delegate.call(any())).thenThrow(new IllegalStateException("offline"));
        var cached = new CachedEmbeddingModel(delegate, "model", true, 1, Duration.ofSeconds(1),
                () -> new JsonCodec(new ObjectMapper()), () -> null);
        for (int i = 0; i < 2; i++) org.assertj.core.api.Assertions.assertThatThrownBy(() -> cached.embed("a")).hasMessage("offline");
        verify(delegate, times(2)).call(any());
    }
}
