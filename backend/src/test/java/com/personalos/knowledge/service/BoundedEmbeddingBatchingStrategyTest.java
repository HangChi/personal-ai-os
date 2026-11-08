package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class BoundedEmbeddingBatchingStrategyTest {
    private final List<Document> documents = IntStream.range(0, 49)
            .mapToObj(i -> new Document("text " + i, Map.of("chunk_index", i))).toList();

    @Test void limitsCountWithoutLosingOrderOrIdentity() {
        var result = new BoundedEmbeddingBatchingStrategy().batch(documents);
        assertThat(result).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(16));
        assertThat(result.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(documents);
        assertThat(result.getFirst().getFirst()).isSameAs(documents.getFirst());
    }

    @Test void preservesTokenBoundariesAndAcceptsEmptyInput() {
        var strategy = new BoundedEmbeddingBatchingStrategy(
                input -> List.of(input.subList(0, 3), input.subList(3, input.size())), 16);
        assertThat(strategy.batch(documents)).extracting(List::size).containsExactly(3, 16, 16, 14);
        assertThat(new BoundedEmbeddingBatchingStrategy(input -> List.of(), 16).batch(List.of())).isEmpty();
    }

    @Test void rejectsInvalidLimitAndPropagatesDelegateFailure() {
        assertThatThrownBy(() -> new BoundedEmbeddingBatchingStrategy(input -> List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedEmbeddingBatchingStrategy(null, 16))
                .isInstanceOf(NullPointerException.class);
        var failure = new IllegalStateException("token budget");
        assertThatThrownBy(() -> new BoundedEmbeddingBatchingStrategy(input -> { throw failure; }, 16)
                .batch(documents)).isSameAs(failure);
    }
}
