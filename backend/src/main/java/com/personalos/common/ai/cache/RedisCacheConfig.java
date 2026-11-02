package com.personalos.common.ai.cache;

import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import redis.clients.jedis.JedisPooled;

@Configuration
@ConditionalOnProperty(name="app.redis.enabled",havingValue="true")
public class RedisCacheConfig {
    @Bean(destroyMethod="close") JedisPooled embeddingRedis(@Value("${app.redis.uri}") URI uri,
            @Value("${app.redis.timeout-ms:150}") int timeout) {
        if (!java.util.Set.of("redis","rediss").contains(uri.getScheme()) || timeout<1 || timeout>5000)
            throw new IllegalArgumentException("Redis 地址或超时配置不合法");
        return new JedisPooled(uri,timeout);
    }
    @Bean SharedEmbeddingCache sharedEmbeddingCache(JedisPooled redis,JsonCodec json,MeterRegistry metrics,
            @Value("${app.redis.namespace}") String namespace) {
        return new RedisEmbeddingCache(redis,json,metrics,namespace);
    }
}
