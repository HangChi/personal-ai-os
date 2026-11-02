package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class CrossEncoderRerankerTest {
    @Test void supportsNativeDashScopePayloadAndResults() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var mapper=new ObjectMapper();
        var input=new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode>();
        server.createContext("/rerank",exchange->{
            input.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] output="{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.95}]},\"usage\":{\"total_tokens\":20}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });server.start();
        try {
            var client=new CrossEncoderReranker("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","test-key","qwen3-rerank","dashscope",mapper);
            var hit=new KnowledgeHitDto(UUID.randomUUID(),"a","a.md",0,"住宿发票",.7);
            assertThat(client.rank("报销凭证",List.of(hit),1000).hits()).containsExactly(hit);
            assertThat(input.get().path("input").path("query").asString()).isEqualTo("报销凭证");
            assertThat(input.get().path("parameters").path("top_n").asInt()).isEqualTo(1);
            assertThat(input.get().path("query").isMissingNode()).isTrue();
        }finally{server.stop(0);}
    }

    @Test void ranksByValidatedIndicesAndKeepsVectorScoresSeparate() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new java.util.concurrent.atomic.AtomicReference<>("{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}");
        server.createContext("/rerank",exchange->{
            String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertThat(input).contains("query","documents","test-model");
            byte[] output=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });server.start();
        try {
            var client=new CrossEncoderReranker("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","","test-model",new ObjectMapper());
            var a=new KnowledgeHitDto(UUID.randomUUID(),"a","a.md",0,"first",.7);var b=new KnowledgeHitDto(UUID.randomUUID(),"b","b.md",0,"second",.6);
            var result=client.rank("住宿",List.of(a,b),1000);assertThat(result.hits()).containsExactly(b,a);assertThat(result.hits().getFirst().score()).isEqualTo(.6);
            assertThat(result.scores().get(b.documentId()+":0")).isEqualTo(.9);
            body.set("{\"results\":[{\"index\":99,\"relevance_score\":1}]}");assertThatThrownBy(()->client.rank("住宿",List.of(a,b),1000)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->client.rank("住宿",List.of(a,b),1)).hasMessageContaining("budget");
        } finally {server.stop(0);}
    }
}
