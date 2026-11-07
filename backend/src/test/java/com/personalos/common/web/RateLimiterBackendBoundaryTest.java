package com.personalos.common.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.personalos.common.config.RateLimitProperties;
class RateLimiterBackendBoundaryTest {
 @Test void backendChoiceAndRedisFailureAreObservableAndFailClosed() {
  var s=new TokenBucketRateLimiter(new RateLimitProperties(true,2,Duration.ofMinutes(1)));
  var db=mock(DatabaseCoordination.class);s.coordinate(db);var metrics=new SimpleMeterRegistry();
  ObjectProvider<RedisRateBucket> p=mock(ObjectProvider.class);s.configureBackend(p,metrics);
  when(db.consume(anyString(),anyInt(),any())).thenReturn(true);assertThat(s.tryAcquire()).isTrue();
  when(db.consume(anyString(),anyInt(),any())).thenThrow(new IllegalStateException("database"));
  assertThatThrownBy(s::tryAcquire).hasMessage("database");
  var redis=mock(RedisRateBucket.class);when(p.getIfAvailable()).thenReturn(redis);s.configureBackend(p,metrics);
  when(redis.consume(anyInt(),any())).thenReturn(true);assertThat(s.tryAcquire()).isTrue();
  when(redis.consume(anyInt(),any())).thenThrow(new IllegalStateException("redis"));assertThat(s.tryAcquire()).isFalse();
  assertThat(metrics.get("personalos.rate.limit.errors").counter().count()).isEqualTo(1);
  assertThat(metrics.get("personalos.rate.limit.acquire").tag("backend","postgres").timer().count()).isEqualTo(2);
 }
 @Test void monotonicClockRollbackDoesNotRefillTheBucket() {
  var s=new TokenBucketRateLimiter(new RateLimitProperties(true,1,Duration.ofHours(1)));assertThat(s.tryAcquire()).isTrue();
  ReflectionTestUtils.setField(s,"lastRefillNanos",System.nanoTime()+Duration.ofHours(1).toNanos());
  assertThat(s.tryAcquire()).isFalse();assertThat(s.availableTokens()).isLessThan(1);
 }
}
