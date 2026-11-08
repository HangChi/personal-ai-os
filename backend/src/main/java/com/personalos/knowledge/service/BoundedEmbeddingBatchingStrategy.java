package com.personalos.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

/** 同时遵守 token 预算与上游输入条数限制；与 Go 的保守 16 条批次一致。 */
public final class BoundedEmbeddingBatchingStrategy implements BatchingStrategy {
    private final BatchingStrategy delegate;
    private final int maxDocuments;

    public BoundedEmbeddingBatchingStrategy() {
        this(new TokenCountBatchingStrategy(), 16);
    }

    BoundedEmbeddingBatchingStrategy(BatchingStrategy delegate, int maxDocuments) {
        this.delegate = Objects.requireNonNull(delegate);
        if (maxDocuments < 1) throw new IllegalArgumentException("Batch limit must be positive");
        this.maxDocuments = maxDocuments;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> result = new ArrayList<>();
        for (List<Document> batch : delegate.batch(documents)) {
            for (int start = 0; start < batch.size(); start += maxDocuments) {
                result.add(new ArrayList<>(batch.subList(start, Math.min(start + maxDocuments, batch.size()))));
            }
        }
        return result;
    }
}
