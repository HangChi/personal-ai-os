package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisRateBucketIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.1-alpine")
            .withExposedPorts(6379);

    @Test void independentCallersCannotSpendTheSameTokens() throws Exception {
        var endpoint = URI.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        var first = new RedisRateBucket(endpoint, "shared");
        var second = new RedisRateBucket(endpoint, "shared");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = IntStream.range(0, 32).mapToObj(i -> (java.util.concurrent.Callable<Boolean>)
                    () -> (i % 2 == 0 ? first : second).consume(5, Duration.ofMinutes(1))).toList();
            int admitted = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) admitted++;
            assertThat(admitted).isEqualTo(5);
        } finally {
            first.close();
            second.close();
        }
    }
}
