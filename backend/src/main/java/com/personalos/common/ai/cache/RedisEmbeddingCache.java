package com.personalos.common.ai.cache;

import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import redis.clients.jedis.JedisPooled;

/** 不写入原文；损坏、过期和连接故障均回退到上游模型。 */
public final class RedisEmbeddingCache implements SharedEmbeddingCache {
    private final JedisPooled redis;
    private final JsonCodec json;
    private final MeterRegistry metrics;
    private final String prefix;
    public RedisEmbeddingCache(JedisPooled redis, JsonCodec json, MeterRegistry metrics, String namespace) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("Redis 缓存必须配置唯一 namespace");
        this.redis=redis; this.json=json; this.metrics=metrics;
        this.prefix="personalos:embedding:"+JsonCodec.sha256Hex(namespace)+":";
    }
    private String key(String identity,String digest) { return prefix+JsonCodec.sha256Hex(identity)+":"+digest; }
    @Override public float[] get(String identity,String digest) {
        try {
            String raw=redis.get(key(identity,digest));
            if (raw==null) return null;
            float[] vector=json.fromJson(raw,float[].class);
            if (vector==null || vector.length==0 || vector.length>65536) return null;
            for(float value:vector) if (!Float.isFinite(value)) return null;
            return vector;
        } catch(RuntimeException ex) { metrics.counter("personalos.embedding.redis.errors","operation","read").increment(); return null; }
    }
    @Override public void put(String identity,String digest,float[] vector,Duration ttl) {
        try { redis.psetex(key(identity,digest),ttl.toMillis(),json.toJson(vector)); }
        catch(RuntimeException ex) { metrics.counter("personalos.embedding.redis.errors","operation","write").increment(); }
    }
}
