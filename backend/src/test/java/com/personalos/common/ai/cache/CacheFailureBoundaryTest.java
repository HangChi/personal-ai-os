package com.personalos.common.ai.cache;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.*;
import redis.clients.jedis.JedisPooled;
import tools.jackson.databind.ObjectMapper;
class CacheFailureBoundaryTest {
 @Test void rejectsInvalidConfigurationAndNeverCachesMalformedVectors(){
  var delegate=mock(EmbeddingModel.class);var json=new JsonCodec(new ObjectMapper());
  for(Duration ttl:List.of(Duration.ZERO,Duration.ofSeconds(-1)))assertThatThrownBy(()->new CachedEmbeddingModel(delegate,"m",true,1,ttl,()->json,()->null)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new CachedEmbeddingModel(delegate,"m",true,0,Duration.ofSeconds(1),()->json,()->null)).isInstanceOf(IllegalArgumentException.class);
  for(var embeddings:List.of(List.<Embedding>of(),List.of(new Embedding(new float[0],0)),List.of(new Embedding(new float[]{1},1)))){
   when(delegate.call(any())).thenReturn(new EmbeddingResponse(embeddings));
   var cached=new CachedEmbeddingModel(delegate,"m",true,1,Duration.ofMinutes(1),()->json,()->null);
   assertThatThrownBy(()->cached.embed("a")).isInstanceOf(IllegalStateException.class);
  }
 }
 @Test void sharedCacheRejectsCorruptionAndFallsBackOnTransportFailures(){
  var redis=mock(JedisPooled.class);var json=mock(JsonCodec.class);var metrics=new SimpleMeterRegistry();
  for(String ns:Arrays.asList(null," "))assertThatThrownBy(()->new RedisEmbeddingCache(redis,json,metrics,ns)).isInstanceOf(IllegalArgumentException.class);
  var cache=new RedisEmbeddingCache(redis,json,metrics,"fixture");when(redis.get(anyString())).thenReturn("synthetic");
  for(float[] vector:Arrays.asList(null,new float[0],new float[65537],new float[]{Float.NaN},new float[]{Float.POSITIVE_INFINITY},new float[]{1,2})){
   when(json.fromJson("synthetic",float[].class)).thenReturn(vector);
   if(vector!=null&&vector.length==2)assertThat(cache.get("id","key")).containsExactly(1,2);else assertThat(cache.get("id","key")).isNull();
  }
  when(redis.get(anyString())).thenThrow(new IllegalStateException("offline"));assertThat(cache.get("id","key")).isNull();
  when(json.toJson(any())).thenThrow(new IllegalStateException("offline"));cache.put("id","key",new float[]{1},Duration.ofSeconds(1));
  assertThat(metrics.get("personalos.embedding.redis.errors").tag("operation","read").counter().count()).isEqualTo(1);
  assertThat(metrics.get("personalos.embedding.redis.errors").tag("operation","write").counter().count()).isEqualTo(1);
 }
}
