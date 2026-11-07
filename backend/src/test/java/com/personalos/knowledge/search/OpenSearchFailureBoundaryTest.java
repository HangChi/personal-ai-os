package com.personalos.knowledge.search;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
class OpenSearchFailureBoundaryTest {
 final ObjectMapper json=new ObjectMapper();
 record Reply(int status,String body){}
 Reply mapping(int dim){return new Reply(200,json.writeValueAsString(Map.of("index",Map.of("mappings",Map.of("properties",Map.of("embedding",Map.of("dimension",dim)))))));}
 OpenSearchGateway fixture(String user,Reply... replies) throws Exception {
  var g=new OpenSearchGateway("https://example.test/","index",user,"synthetic",2,json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(g,"http",client);
  var queue=new ArrayDeque<Reply>(List.of(replies));
  when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
   HttpRequest request=call.getArgument(0);if(!user.isEmpty())assertThat(request.headers().firstValue("Authorization")).hasValueSatisfying(s->assertThat(s).startsWith("Basic "));
   if(queue.isEmpty())throw new AssertionError("unexpected OpenSearch request");var reply=queue.remove();HttpResponse<InputStream> r=mock(HttpResponse.class);when(r.statusCode()).thenReturn(reply.status());when(r.body()).thenReturn(new ByteArrayInputStream(reply.body().getBytes(StandardCharsets.UTF_8)));return r;
  });return g;
 }
 @Test void indexCreationAndMappingFailuresAreNotSilenced() throws Exception {
  for(int created:new int[]{200,400,201,500}){var g=fixture("user",new Reply(404,""),new Reply(created,""),mapping(2));if(created==200||created==400){g.initialize();g.initialize();}else assertThatThrownBy(g::initialize).isInstanceOf(IllegalStateException.class).hasMessageContaining("创建失败");}
  var inaccessible=fixture("",new Reply(401,""));assertThatThrownBy(inaccessible::initialize).hasMessageContaining("不可访问");
  for(Reply mapping:List.of(mapping(3),new Reply(500,"{}"))){var g=fixture("",new Reply(200,""),mapping);assertThatThrownBy(g::initialize).hasMessageContaining("维度不一致");}
  for(String endpoint:List.of("ftp://example.test","https://u:p@example.test"))assertThatThrownBy(()->new OpenSearchGateway(endpoint,"index","","",2,json)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new OpenSearchGateway("http://example.test","Bad/Index","","",2,json)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void externalVersionProjectionAcceptsOnlyDocumentedStatuses() throws Exception {
  for(boolean deletion:List.of(false,true))for(int code:new int[]{200,201,404,409,403,500}){var g=fixture("",new Reply(200,""),mapping(2),new Reply(code,"{}"));if(code==403||code==500)assertThatThrownBy(()->g.project(UUID.randomUUID(),2,deletion?null:"text",new float[]{1,0})).hasMessageContaining("写入失败");else g.project(UUID.randomUUID(),2,deletion?null:"text",new float[]{1,0});}
  var g=fixture("",new Reply(200,""),mapping(2),new Reply(503,"{}"));assertThatThrownBy(()->g.search("q",new float[]{1,0},3)).hasMessageContaining("召回失败");
 }
 @Test void oversizedAndInterruptedResponsesAreClosedAndSanitized() throws Exception {
  var huge=fixture("",new Reply(200,"x".repeat(2_000_001)));assertThatThrownBy(huge::initialize).hasMessageContaining("响应过大");
  for(Exception failure:List.of(new IOException("private-key"),new InterruptedException())){
   var g=fixture("");var client=mock(HttpClient.class);ReflectionTestUtils.setField(g,"http",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(failure);
   try{assertThatThrownBy(g::initialize).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-key");if(failure instanceof InterruptedException)assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
  }
 }
}
