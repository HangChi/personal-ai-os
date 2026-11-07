package com.personalos.common.ai.config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;
import com.personalos.common.ai.config.dto.ModelListResult;
import com.personalos.common.exception.BusinessException;
class ModelListProbeBoundaryTest {
 private final ObjectMapper json=new ObjectMapper();
 private HttpResponse<String> response(int code,String body){return new StubResponse(code,body);}
 private record StubResponse(int statusCode,String body) implements HttpResponse<String>{
 public HttpRequest request(){return HttpRequest.newBuilder(URI.create("https://example.test")).build();}
 public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
 public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
 public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
 public URI uri(){return URI.create("https://example.test");}
 public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
 }
 @Test void metadataFieldsRespectNumericBoundsAndTraversalDepth(){
  for(String key:List.of("context_length","context_window","max_model_len","max_context_length","max_context_window","context_window_tokens","max_context_window_tokens","max_position_embeddings","n_ctx","max_input_tokens","max_prompt_tokens")){
   for(Object value:List.of(1024,33554432,"32,768")){var node=json.valueToTree(Map.of(key,value));assertThat(ModelListProbeService.readWindow(node)).as(key).isEqualTo(value.equals("32,768")?32768:((Number)value).intValue());}
   for(Object value:List.of(0,-1,1023,33554433,1.5,"","-2048","x","1".repeat(25),"9".repeat(24),true)){assertThat(ModelListProbeService.readWindow(json.valueToTree(Map.of(key,value)))).as(key+value).isNull();}
  }
  for(String container:List.of("extra_info","data","model_info","meta","metadata","limits","capabilities","top_provider","config","details")){
   assertThat(ModelListProbeService.readWindow(json.valueToTree(Map.of(container,Map.of("nested",List.of(1,Map.of("context_length",8192))))))).isEqualTo(8192);
  }
  assertThat(ModelListProbeService.readWindow(json.valueToTree(Map.of("model_info",Map.of("bad.context_length",0,"valid.context_length",16384))))).isEqualTo(16384);
  Object deep=Map.of("context_length",8192);for(int i=0;i<6;i++)deep=Map.of("child",deep);assertThat(ModelListProbeService.readWindow(json.valueToTree(Map.of("meta",deep)))).isNull();
  for(Object env:List.of(Map.of(),Map.of("max_tokens",32768),Map.of("max_input_tokens",30720),Map.of("max_tokens","32768","max_input_tokens",30720),Map.of("max_tokens",32768,"max_input_tokens","30720"),Map.of("max_tokens",32768,"max_input_tokens",20000))){assertThat(ModelListProbeService.isDerivedFromDefaultMaxTokens(json.valueToTree(Map.of("default_envs",env)))).isFalse();}
 }
 @Test void listResponsesHandleStatusesAndAlternateShapes() throws Exception {
  var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(404,""));
  var uri=URI.create("https://example.test/v1/models");var request=ModelListProbeService.buildRequest(ModelProtocol.OPENAI,uri,"k");
  for(int status:new int[]{100,199,300,401,403,404,405,429,500})for(String body:Arrays.asList(null,"","error","x".repeat(201))){ModelListResult r=ReflectionTestUtils.invokeMethod(service,"interpret",ModelProtocol.OPENAI,"https://example.test",uri,request,response(status,body),1L,List.of());assertThat(r.ok()).isFalse();}
  for(String body:List.of("null","","invalid","{}","[]")){ModelListResult r=ReflectionTestUtils.invokeMethod(service,"interpret",ModelProtocol.OPENAI,"https://example.test",uri,request,response(200,body),1L,List.of());assertThat(r.ok()).isFalse();}
  for(ModelProtocol p:ModelProtocol.values())for(Object shape:List.of(Map.of("data",List.of(Map.of("id","m","context_length",8192))),Map.of("models",List.of(Map.of("id","m","name","m","context_length",8192))),List.of(Map.of("id","m","context_length",8192)))){
   ModelListResult r=ReflectionTestUtils.invokeMethod(service,"interpret",p,"https://example.test",uri,request,response(200,json.writeValueAsString(shape)),1L,List.of());if(p!=ModelProtocol.OLLAMA||!(shape instanceof List)) assertThat(r.ok()).isTrue();
  }
  for(String raw:Arrays.asList(null," ","ftp://bad","http://bad host")) assertThat(service.probe(null,raw,"k").ok()).isFalse();
  for(ModelProtocol p:ModelProtocol.values()){var r=ModelListProbeService.buildRequest(p,uri,null);if(p!=ModelProtocol.ANTHROPIC)assertThat(r.headers().firstValue("Authorization")).isEmpty();}
  for(Throwable e:List.of(new UnknownHostException(),new ConnectException(),new HttpTimeoutException(""),new SocketTimeoutException(),new SSLException(""),new IOException(),new IOException(""),new IOException("TUNNEL"),new IOException("reset"),new IllegalStateException(),new IllegalStateException(" "),new IllegalStateException("secret"))){String msg=ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"describeNetworkError",e);assertThat(msg).isNotBlank();}
 }
 @Test void singleWindowFallbackAndFailuresAreDistinct() throws Exception {
  for(String model:Arrays.asList(null," "))assertThatThrownBy(()->new ModelListProbeService(json).probeWindow(ModelProtocol.OPENAI,"https://example.test","k",model)).isInstanceOf(BusinessException.class);
  for(String base:Arrays.asList(null," "))assertThatThrownBy(()->new ModelListProbeService(json).probeWindow(ModelProtocol.OPENAI,base,"k","m")).isInstanceOf(BusinessException.class);
  for(Object metadata:List.of(Map.of(),Map.of("max_input_tokens",4096),Map.of("max_input_tokens",4096,"context_length",8192),Map.of("max_input_tokens",8192,"context_length",4096),Map.of("context_length",8192),Map.of("extra_info",Map.of("default_envs",Map.of("max_tokens",32768,"max_input_tokens",30720))))){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,json.writeValueAsString(metadata)),response(200,json.writeValueAsString(List.of(Map.of("name","m","context_length",8192)))));assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","m").ok()).isTrue();
  }
  for(int code:new int[]{199,401,403,404,405,429,500}){var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(code,""));assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","unknown-local-name").ok()).isFalse();}
  for(String body:List.of("null","invalid","{}","[]")){var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,body));assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","unknown-local-name").ok()).isFalse();}
 }

 @Test void prioritiesFallbackAndMixedModelShapes() throws Exception {
  assertThat(ModelListProbeService.orderedDetailTargets(null,null)).isEmpty();
  assertThat(ModelListProbeService.orderedDetailTargets(List.of(),null)).isEmpty();
  var names=java.util.stream.IntStream.range(0,25).mapToObj(i->"model-"+i).toList();
  assertThat(ModelListProbeService.orderedDetailTargets(names,Arrays.asList(null,"missing","model-24","model-24"))).hasSize(12).startsWith("model-24");
  assertThat(ModelListProbeService.orderedDetailTargets(names,names)).hasSize(12);
  var uri=URI.create("https://example.test/v1/models");var request=ModelListProbeService.buildRequest(ModelProtocol.OPENAI,uri,"k");
  for(String shape:List.of("data","models","array")){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);
   var rows=List.of(Map.of("id","not-it"),Map.of("name","m","context_length",8192));Object root=shape.equals("array")?rows:Map.of(shape,rows);
   when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"null"),response(200,json.writeValueAsString(root)));
   assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","m").window()).isEqualTo(8192);
  }
  for(String name:List.of("gpt-4o","claude-3-5-sonnet-20241022","qwen-max","deepseek-chat","unknown-private-model")){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}"),response(200,"[]"));
   var r=service.probeWindow(ModelProtocol.OPENAI,"https://api.openai.com","k",name);if(name.equals("unknown-private-model"))assertThat(r.ok()).isFalse();else assertThat(r.source()).isIn("reference","catalog");
  }
  for(int status:new int[]{199,300,401,403,404,405,429,500}){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}"),response(status,"bad"));
   assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","m").ok()).isFalse();
  }
  for(String body:List.of("","null","[]","{}","invalid")){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}"),response(200,body));assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","m").ok()).isFalse();
  }
  for(Throwable e:List.of(new HttpTimeoutException(""),new IOException(),new InterruptedException(),new IllegalStateException())){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}")).thenThrow(e);try{assertThat(service.probeWindow(ModelProtocol.OPENAI,"https://example.test","k","m").ok()).isFalse();}finally{Thread.interrupted();}
  }
  for(Throwable e:List.of(new IOException(),new IllegalStateException())){var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(e);assertThat(service.probe(null,"https://example.test","k").ok()).isFalse();}
  assertThat(new ModelListProbeService(json).probe(null,"https://example.test",null).ok()).isFalse();
  assertThat(new ModelListProbeService(json).probe(null,"https://example.test"," ").ok()).isFalse();
  for(String raw:Arrays.asList(null," "))assertThat(ModelListProbeService.blockedReason(null,raw,"k")).isPresent();
  for(Object root:List.of(Map.of(),List.of(Map.of("id",1),Map.of("id","other","context_length",8192),Map.of("id","m","context_length",8192)))){
   Map<String,Integer> found=ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"collectContextWindows",json.valueToTree(root),"id",List.of("m"));assertThat(found).hasSize(root instanceof List?1:0);
  }
  assertThat((Map<?,?>)ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"collectContextWindows",null,"id",List.of())).isEmpty();
  assertThat((Map<?,?>)ReflectionTestUtils.invokeMethod(new ModelListProbeService(json),"lookupDetailWindows",ModelProtocol.OPENAI,"https://example.test",request,List.of(),List.of())).isEmpty();
  var nested=Map.of("meta",Map.of("nested",List.of(1,List.of(Map.of("context_length",4096)))));assertThat(ModelListProbeService.readWindow(json.valueToTree(nested))).isEqualTo(4096);
 }

 @Test void officialReferenceFallbackAndMalformedMetadataAreConservative() throws Exception {
  for(String name:List.of("qwen3-max","qwen-plus","gpt-4o","unknown")){
   var service=new ModelListProbeService(json);var client=mock(HttpClient.class);ReflectionTestUtils.setField(service,"httpClient",client);
   when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}"),response(200,json.writeValueAsString(Map.of("data",List.of(Map.of("id",name))))));
   var result=service.probeWindow(ModelProtocol.OPENAI,"https://dashscope.aliyuncs.com/compatible-mode","k",name);
   assertThat(result.ok()).isEqualTo(!name.equals("unknown"));
  }
  var sink=new ArrayList<String>();
  ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"collectIds",null,"id",sink);
  ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"collectIds",json.readTree("{}"),"id",sink);
  ReflectionTestUtils.invokeMethod(ModelListProbeService.class,"collectIds",json.valueToTree(List.of(Map.of("id",1),Map.of("id","valid"))),"id",sink);
  assertThat(sink).containsExactly("valid");
  var service=new ModelListProbeService(json);
  for(var node:List.of(json.readTree("{}"),json.readTree("[]"))) assertThat((Map<?,?>)ReflectionTestUtils.invokeMethod(service,"collectContextWindows",node,"id",List.of())).isEmpty();
  assertThat((Map<?,?>)ReflectionTestUtils.invokeMethod(service,"collectContextWindows",null,"id",List.of("x"))).isEmpty();
  assertThat(ModelListProbeService.readWindow(json.valueToTree(Map.of("meta",Map.of("nested",List.of(List.of(0,Map.of("context_length",8192)))))))).isEqualTo(8192);
 }

 @Test void referenceFallbackUsesInputLimitInsteadOfTotalWindow() throws Exception {
  var service=new ModelListProbeService(json);var client=mock(HttpClient.class);
  ReflectionTestUtils.setField(service,"httpClient",client);
  when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{}"),response(200,"[]"));
  var result=service.probeWindow(ModelProtocol.OPENAI,"https://dashscope.aliyuncs.com/compatible-mode","synthetic","qwen3.8-flash");
  assertThat(result.ok()).isTrue();
  assertThat(result.source()).isEqualTo("reference");
  assertThat(result.window()).isEqualTo(983616);
 }
}
