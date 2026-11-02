package com.personalos.common.ai.cache;

import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class EmbeddingCacheConfig {
    @Bean
    static BeanPostProcessor embeddingCacheDecorator(Environment env, ObjectProvider<JsonCodec> json,
                                                     ObjectProvider<MeterRegistry> metrics) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof EmbeddingModel model) || bean instanceof CachedEmbeddingModel) return bean;
                return new CachedEmbeddingModel(model, name,
                        env.getProperty("app.embedding-cache.enabled", Boolean.class, false),
                        env.getProperty("app.embedding-cache.capacity", Integer.class, 1000),
                        env.getProperty("app.embedding-cache.ttl", Duration.class, Duration.ofMinutes(10)),
                        json::getObject, metrics::getIfAvailable);
            }
        };
    }
}
