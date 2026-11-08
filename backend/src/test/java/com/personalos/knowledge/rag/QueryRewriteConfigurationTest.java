package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.governance.KnowledgeGovernance;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class QueryRewriteConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings={"default","true","false","property-override"})
    void actualYamlBindsEnvironmentAliasAndDefaults(String setting) throws IOException {
        var json=new ObjectMapper();
        var recall=mock(KnowledgeRetrievalService.class);
        var model=mock(RagModelGateway.class);
        var governance=mock(KnowledgeGovernance.class);
        when(recall.hasContent()).thenReturn(true);
        when(recall.candidates(anyString(),anyInt())).thenReturn(List.of());
        when(governance.enrich(anyList())).thenAnswer(call->call.getArgument(0));
        when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.readTree("{\"queries\":[]}"));
        var sources=new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml"));
        var runner=new ApplicationContextRunner()
            .withInitializer(context->sources.forEach(source->context.getEnvironment().getPropertySources().addLast(source)))
            .withBean(KnowledgeRetrievalService.class,()->recall)
            .withBean(org.springframework.ai.embedding.EmbeddingModel.class,()->mock(org.springframework.ai.embedding.EmbeddingModel.class))
            .withBean(RagModelGateway.class,()->model)
            .withBean(EvidenceReview.class,()->new EvidenceReview(model,json))
            .withBean(KnowledgeGovernance.class,()->governance)
            .withBean(KnowledgeProperties.class,()->new KnowledgeProperties(1024,4,.3,50000,List.of("md"),10,1200,0,true,60,1))
            .withBean(ObjectMapper.class,()->json)
            .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
            .withUserConfiguration(RagRetrievalPipeline.class);
        if(!setting.equals("default")) runner=runner.withPropertyValues("RAG_QUERY_REWRITE_ENABLED="+(!setting.equals("false")));
        if(setting.equals("property-override")) runner=runner.withPropertyValues("app.knowledge.rag.query-rewrite-enabled=false");
        runner.run(context->{
            assertThat(context).hasNotFailed();
            var result=context.getBean(RagRetrievalPipeline.class).retrieve("报销标准",4);
            boolean enabled=setting.equals("default")||setting.equals("true");
            assertThat(result.stages().get(1).status()).isEqualTo(enabled?"OK":"SKIPPED");
            verify(model,times(enabled?1:0)).call(anyString(),anyString(),anyLong());
        });
    }
}
