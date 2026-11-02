package com.personalos.common.ai.cache;

import java.time.Duration;

/** 可再生二级缓存，不能成为模型请求或数据库操作的正确性前提。 */
public interface SharedEmbeddingCache {
    float[] get(String modelIdentity, String digest);
    void put(String modelIdentity, String digest, float[] vector, Duration ttl);
}
