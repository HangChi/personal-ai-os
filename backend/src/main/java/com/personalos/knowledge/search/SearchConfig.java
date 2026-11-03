package com.personalos.knowledge.search;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.service.VectorStoreInspector;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.knowledge.search.enabled",havingValue="true")
public class SearchConfig {
    @Bean OpenSearchGateway openSearchGateway(@Value("${app.knowledge.search.endpoint:http://localhost:9200}") String endpoint,
            @Value("${app.knowledge.search.index:personalos-chunks-v1}") String index,
            @Value("${app.knowledge.search.username:}") String user,@Value("${app.knowledge.search.password:}") String password,
            KnowledgeProperties properties,ObjectMapper mapper) {
        return new OpenSearchGateway(endpoint,index,user,password,properties.embeddingDimensions(),mapper);
    }
    @Bean SearchProjection searchProjection(JdbcTemplate db,VectorStoreInspector inspector,OpenSearchGateway gateway,MeterRegistry meters,com.personalos.common.config.RuntimeRole role) {
        return new SearchProjection(db,inspector,gateway,meters,role);
    }
}
