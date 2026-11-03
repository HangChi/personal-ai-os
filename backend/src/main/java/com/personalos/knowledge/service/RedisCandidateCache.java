package com.personalos.knowledge.service;

import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import redis.clients.jedis.JedisPooled;

@Component
@ConditionalOnExpression("${app.redis.enabled:false} && ${app.knowledge.candidate-cache.enabled:false}")
public class RedisCandidateCache {
    private static final int MAX_BYTES = 256 * 1024;
    private final JedisPooled redis;
    private final JdbcTemplate db;
    private final JsonCodec json;
    private final MeterRegistry meters;
    private final String prefix;

    public RedisCandidateCache(JedisPooled redis, JdbcTemplate db, JsonCodec json, MeterRegistry meters,
                               KnowledgeProperties properties, Environment env,
                               @Value("${app.redis.namespace}") String namespace) {
        this.redis = redis;
        this.db = db;
        this.json = json;
        this.meters = meters;
        String configuration = java.util.stream.Stream.of("spring.ai.model.embedding", "spring.ai.openai.base-url",
                "spring.ai.openai.embedding.model", "spring.ai.ollama.base-url", "spring.ai.ollama.embedding.model",
                "app.embedding-cache.revision", "app.knowledge.search.endpoint", "app.knowledge.search.index")
                .map(key -> env.getProperty(key, "")).collect(java.util.stream.Collectors.joining("\n"));
        prefix = "personalos:rag:candidates:v1:" + JsonCodec.sha256Hex(namespace + "\n" + configuration
                + "\n" + json.toJson(List.of(properties.embeddingDimensions(), properties.similarityThreshold(),
                        properties.hybridSearchEnabled(), properties.rrfK(), properties.keywordChannelWeight()))) + ":";
    }

    public List<KnowledgeHitDto> get(String query, int limit, BooleanSupplier externalReady,
                                     Function<Boolean, List<KnowledgeHitDto>> recall) {
        // Uncommitted database changes must never enter the shared cache.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return recall.apply(externalReady.getAsBoolean());
        UUID version;
        try {
            version = generation();
        } catch (RuntimeException ex) {
            count("read_error");
            return recall.apply(externalReady.getAsBoolean());
        }
        // Capture the version first. Any commit between these reads invalidates the write below.
        boolean ready = externalReady.getAsBoolean();
        String key = prefix + version + ":" + JsonCodec.sha256Hex(limit + "\n" + ready + "\n" + query);
        try {
            String raw = redis.get(key);
            if (raw != null && raw.length() <= MAX_BYTES) {
                var hits = List.of(json.fromJson(raw, KnowledgeHitDto[].class));
                if (version.equals(generation())) {
                    count("hit");
                    return hits;
                }
            }
        } catch (RuntimeException ex) {
            count("read_error");
            return recall.apply(ready);
        }
        count("miss");
        List<KnowledgeHitDto> hits = recall.apply(ready);
        try {
            String payload = json.toJson(hits);
            if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_BYTES
                    && version.equals(generation())) redis.setex(key, 20, payload);
        } catch (RuntimeException ex) {
            count("write_error");
        }
        return hits;
    }

    private UUID generation() {
        return db.queryForObject("SELECT generation FROM knowledge_candidate_generation WHERE id=1", UUID.class);
    }

    private void count(String result) {
        meters.counter("personalos.knowledge.candidate.cache", "result", result).increment();
    }
}
