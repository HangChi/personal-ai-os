package com.personalos.knowledge.search;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
class OpenSearchGatewayIntegrationTest {
    @Test void chineseHybridVersionFenceAndDeletion() {
        try(var server=new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.9.0"))
                .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG","true").withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m")
                .withExposedPorts(9200).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/_cluster/health").forPort(9200))) {
            server.start();
            var gateway=new OpenSearchGateway("http://"+server.getHost()+":"+server.getMappedPort(9200),"test-chunks","","",3,new ObjectMapper());
            UUID id=UUID.randomUUID(),second=UUID.randomUUID();
            gateway.project(id,10,"报销规定：差旅住宿限额为每天五百元",new float[]{1,0,0});
            gateway.project(second,11,"绿萝浇水养护",new float[]{0,1,0});
            var hits=gateway.search("差旅住宿限额",new float[]{1,0,0},2);
            assertThat(hits.lexical()).contains(id).doesNotContain(second);
            assertThat(hits.vector()).startsWith(id);
            gateway.project(id,9,"旧版绿萝",new float[]{0,1,0});
            assertThat(gateway.search("差旅住宿",new float[]{1,0,0},2).lexical()).contains(id);
            gateway.project(id,12,null,null);
            gateway.project(id,10,"旧数据不可复活",new float[]{1,0,0});
            assertThat(gateway.search("住宿",new float[]{1,0,0},2).vector()).doesNotContain(id);
            assertThatThrownBy(()->new OpenSearchGateway("http://"+server.getHost()+":"+server.getMappedPort(9200),"test-chunks","","",4,new ObjectMapper()).initialize())
                .hasMessageContaining("维度");
            server.stop();
            assertThatThrownBy(()->gateway.search("报销",new float[]{1,0,0},2)).isInstanceOf(IllegalStateException.class);
        }
    }
}
