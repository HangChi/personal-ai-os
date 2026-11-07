package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
class CrossEncoderBoundaryTest {
 @Test void invalidConfigurationAndInvalidScoresCannotBecomeEvidence()throws Exception{
  for(String[] conf:new String[][]{{"ftp://example.com","m","cohere"},{"https://u:p@example.com","m","cohere"},{"https://example.com"," ","cohere"},{"https://example.com","m","other"}})assertThatThrownBy(()->new CrossEncoderReranker(conf[0],"",conf[1],conf[2],new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
  var hit=new KnowledgeHitDto(UUID.randomUUID(),"a","a.md",0,"content",.7);
  for(String body:List.of("{}","{\"results\":[]}","{\"results\":[{\"index\":-1,\"relevance_score\":0.5}]}","{\"results\":[{\"index\":0}]}","{\"results\":[{\"index\":0,\"relevance_score\":-1}]}","{\"results\":[{\"index\":0,\"relevance_score\":2}]}","{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":0,\"relevance_score\":0.4}]}")){
   var client=new CrossEncoderReranker("https://example.com","","m",new ObjectMapper());var http=mock(HttpClient.class);ReflectionTestUtils.setField(client,"http",http);
   @SuppressWarnings("unchecked") HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
   assertThatThrownBy(()->client.rank("q",List.of(hit),1000)).isInstanceOf(IllegalArgumentException.class);
  }
 }
 @Test void upstreamFailuresAndCancellationPreserveDegradationContract()throws Exception{
  var hit=new KnowledgeHitDto(UUID.randomUUID(),"a","a.md",0,"content",.7);
  for(String mode:List.of("status","oversized","io","interrupted","usage")){
   var client=new CrossEncoderReranker("https://example.com","key","m",new ObjectMapper());var http=mock(HttpClient.class);ReflectionTestUtils.setField(client,"http",http);
   if(mode.equals("io"))when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException());
   else if(mode.equals("interrupted"))when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
   else{
    @SuppressWarnings("unchecked") HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(mode.equals("status")?503:200);
    String body=mode.equals("oversized")?"x".repeat(1000001):"{\"usage\":{\"total_tokens\":12},\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
    when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
   }
   try{if(mode.equals("usage")){var meters=new SimpleMeterRegistry();client.metrics(meters);assertThat(client.rank("q",List.of(hit),1000).hits()).containsExactly(hit);assertThat(meters.get("personalos.knowledge.rag.rerank.tokens").counter().count()).isEqualTo(12);}else assertThatThrownBy(()->client.rank("q",List.of(hit),1000)).isInstanceOf(IllegalStateException.class);if(mode.equals("interrupted"))assertThat(Thread.interrupted()).isTrue();}finally{Thread.interrupted();}
  }
 }
}
