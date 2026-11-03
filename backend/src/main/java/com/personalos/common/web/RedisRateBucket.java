package com.personalos.common.web;

import com.personalos.common.json.JsonCodec;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

@Component
@ConditionalOnProperty(name = "app.rate-limit.redis.enabled", havingValue = "true")
public class RedisRateBucket {
    private static final String TAKE = """
            local capacity = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local previous = redis.call('HMGET', KEYS[1], 'tokens', 'at', 'capacity', 'period')
            if previous[3] and (previous[3] ~= ARGV[1] or previous[4] ~= ARGV[2]) then
                return redis.error_reply('Inconsistent rate limit configuration')
            end
            local t = redis.call('TIME')
            local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
            local at = tonumber(previous[2]) or now
            now = math.max(now, at)
            local tokens = math.min(capacity, (tonumber(previous[1]) or capacity) + (now-at)*capacity/period)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', now, 'capacity', ARGV[1], 'period', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], period + 1000)
            return allowed
            """;
    private final JedisPooled redis;
    private final String key;

    public RedisRateBucket(@Value("${app.rate-limit.redis.uri}") URI endpoint,
                           @Value("${app.rate-limit.redis.namespace}") String namespace) {
        if (!Set.of("redis","rediss").contains(endpoint.getScheme()) || endpoint.getHost() == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Invalid rate limit Redis configuration");
        }
        redis = new JedisPooled(endpoint, 150);
        key = "personalos:rate:" + JsonCodec.sha256Hex(namespace);
    }

    public boolean consume(int capacity, Duration period) {
        long millis = Math.max(1, period.toMillis());
        return Long.valueOf(1).equals(redis.eval(TAKE, List.of(key),
                List.of(Integer.toString(capacity), Long.toString(millis))));
    }

    @PreDestroy public void close() { redis.close(); }
}
