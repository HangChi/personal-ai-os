package com.personalos.common.ai.cache;

import com.github.benmanes.caffeine.cache.*;
import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

/** 按模型实例和完整选项隔离；默认仅观测重复率，不缓存向量、不改变模型调用。 */
public final class CachedEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final boolean enabled;
    private final String model;
    private final Supplier<JsonCodec> json;
    private final Supplier<MeterRegistry> metrics;
    private final Cache<String, float[]> vectors;
    private final Cache<String, Boolean> seen;
    public CachedEmbeddingModel(EmbeddingModel delegate, String model, boolean enabled, int capacity,
                                Duration ttl, Supplier<JsonCodec> json, Supplier<MeterRegistry> metrics) {
        if (capacity < 1 || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("嵌入缓存容量和 TTL 必须为正数");
        this.delegate = delegate;
        this.enabled = enabled;
        this.model = model;
        this.json = json;
        this.metrics = metrics;
        this.vectors = Caffeine.newBuilder().maximumSize(capacity).expireAfterWrite(ttl).build();
        this.seen = Caffeine.newBuilder().maximumSize(capacity).expireAfterWrite(ttl).build();
    }
    @Override public String getEmbeddingContent(Document document) { return delegate.getEmbeddingContent(document); }
    @Override public float[] embed(Document document) { return embed(getEmbeddingContent(document)); }
    @Override public int dimensions() { return delegate.dimensions(); }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        String options;
        try {
            var effective = request.getOptions() == null ? EmbeddingOptions.builder().build() : request.getOptions();
            options = effective.getClass().getName() + json.get().toJson(effective);
        } catch (RuntimeException ex) { count("bypass"); return invoke(request); }
        List<String> keys = request.getInstructions().stream()
                .map(text -> JsonCodec.sha256Hex(options + "\n" + text)).toList();
        if (!enabled) {
            for (String key : keys) count(seen.asMap().putIfAbsent(key, true) == null ? "observed_new" : "observed_repeat");
            return invoke(request);
        }
        float[][] outputs = new float[keys.size()][];
        List<String> missing = new ArrayList<>();
        Map<String, Integer> missingKeys = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            outputs[i] = vectors.getIfPresent(keys.get(i));
            count(outputs[i] == null ? "miss" : "hit");
            if (outputs[i] == null && !missingKeys.containsKey(keys.get(i))) {
                missingKeys.put(keys.get(i), missing.size());
                missing.add(request.getInstructions().get(i));
            }
        }
        EmbeddingResponse response = null;
        Map<Integer, float[]> computed = new HashMap<>();
        if (!missing.isEmpty()) {
            response = invoke(new EmbeddingRequest(missing, request.getOptions()));
            for (Embedding result : response.getResults()) computed.put(result.getIndex(), result.getOutput());
            if (computed.size() != missing.size()) throw new IllegalStateException("嵌入结果数量不匹配");
            for (var entry : missingKeys.entrySet()) {
                float[] vector = computed.get(entry.getValue());
                if (vector == null || vector.length == 0) throw new IllegalStateException("嵌入结果为空");
                vectors.put(entry.getKey(), vector.clone());
            }
        }
        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            float[] output = outputs[i] == null ? computed.get(missingKeys.get(keys.get(i))) : outputs[i];
            results.add(new Embedding(output.clone(), i));
        }
        return response == null ? new EmbeddingResponse(results) : new EmbeddingResponse(results, response.getMetadata());
    }
    private EmbeddingResponse invoke(EmbeddingRequest request) {
        MeterRegistry registry = metrics.get();
        long start = System.nanoTime();
        try { return delegate.call(request); }
        finally {
            if (registry != null) registry.timer("personalos.embedding.duration", "model", model)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    private void count(String result) {
        MeterRegistry registry = metrics.get();
        if (registry != null) registry.counter("personalos.embedding.cache.requests", "model", model, "result", result).increment();
    }
}
