package com.personalos.common.ai.cache;

import static org.assertj.core.api.Assertions.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.support.DeterministicEmbeddingModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.*;
import redis.clients.jedis.JedisPooled;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class RedisEmbeddingCacheIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.2.1-alpine"))
            .withExposedPorts(6379).withCommand("redis-server","--requirepass","test-password");
    @Test void sharesVectorsAcrossInstancesWithExpiryNamespaceIsolationAndOutageFallback() throws Exception {
        var json=new JsonCodec(new ObjectMapper());
        var metrics=new SimpleMeterRegistry();
        try(var client=new JedisPooled(URI.create("redis://:test-password@"+REDIS.getHost()+":"+REDIS.getMappedPort(6379)),150)) {
            var cache=new RedisEmbeddingCache(client,json,metrics,"test-user");
            var delegate=new DeterministicEmbeddingModel(8);
            var first=new CachedEmbeddingModel(delegate,"model",true,10,Duration.ofMinutes(1),()->json,()->metrics,"identity",()->cache);
            var second=new CachedEmbeddingModel(delegate,"model",true,10,Duration.ofMinutes(1),()->json,()->metrics,"identity",()->cache);
            assertThat(first.embed("private text")).containsExactly(second.embed("private text"));
            assertThat(metrics.get("personalos.embedding.cache.requests").tag("result","shared_hit").counter().count()).isEqualTo(1);
            assertThat(client.keys("*")).allMatch(key -> !key.contains("private text"));
            assertThat(new RedisEmbeddingCache(client,json,metrics,"other-user").get("identity","key")).isNull();
            cache.put("identity","expiry",new float[]{1},Duration.ofMillis(80));
            Thread.sleep(150);
            assertThat(cache.get("identity","expiry")).isNull();
            REDIS.stop();
            var third=new CachedEmbeddingModel(delegate,"model",true,10,Duration.ofSeconds(1),()->json,()->metrics,"identity",()->cache);
            assertThat(third.embed("outage")).hasSize(8);
            assertThat(metrics.get("personalos.embedding.redis.errors").tag("operation","read").counter().count()).isPositive();
        }
    }
}
