package com.personalos.common.ai.config;

import static org.mockito.Mockito.*;
import com.personalos.common.ai.*;
import com.personalos.common.exception.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** 固定Java源的离线差分：只调用本机随机端口，不装配应用或读取环境配置。 */
public class P3ContractOracle {
 public static void main(String[] args) throws Exception {
  var mapper=JsonMapper.builder().build();var input=mapper.readTree(System.in);var output=new LinkedHashMap<String,Object>();
  var windows=new ArrayList<Object>();for(var c:input.get("metadata"))windows.add(ModelListProbeService.readWindow(mapper.readTree(c.asText())));output.put("metadata",windows);
  var known=new ArrayList<Object>();for(var c:input.get("known"))known.add(ModelContextWindows.lookup(c.isNull()?null:c.asText()));output.put("known",known);
  var references=new ArrayList<Object>();for(var c:input.get("references"))references.add(ModelWindowReferences.find(c.get("name").asText(),c.get("base").asText()));output.put("references",references);
  var binds=new ArrayList<Object>();
  for(var c:input.get("bindings")){
   var clock=Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"),ZoneOffset.UTC);
   var repo=mock(AiModelProfileRepository.class);var models=mock(AiModelProfileModelRepository.class);var legacy=mock(AiModelConfigRepository.class);
   when(legacy.findSingleton()).thenReturn(Optional.empty());when(repo.save(any(AiModelProfileEntity.class))).thenAnswer(inv->{var entity=inv.getArgument(0);ReflectionTestUtils.setField(entity,"id",UUID.fromString("11111111-2222-3333-4444-555555555555"));return entity;});when(legacy.save(any(AiModelConfigEntity.class))).thenAnswer(inv->inv.getArgument(0));
   var config=new ModelConfigService(legacy,mock(OpenAiChatModelFactory.class),mock(AiChatClientProvider.class),clock,"https://api.openai.com","","gpt-4o-mini","text-embedding-3-small","0.7");
   var profiles=new ModelProfileService(repo,models,legacy,config,mock(AiChatClientProvider.class),clock);
   var probe=mock(ModelListProbeService.class);var connection=mock(ModelConnectionTestService.class);
   var mvc=MockMvcBuilders.standaloneSetup(new ModelProfileController(profiles,config,connection,probe),new ModelConfigController(config,connection)).setControllerAdvice(new GlobalExceptionHandler()).build();
   String path=c.get("path").asText();String method=c.get("method").asText();var req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path).contentType(c.get("contentType").asText()).content(c.get("body").asText());
   var result=mvc.perform(req).andReturn().getResponse();var body=mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8));var row=new LinkedHashMap<String,Object>();row.put("status",result.getStatus());row.put("code",body.get("code").asInt());row.put("message",body.get("message").asText());binds.add(row);
  }output.put("bindings",binds);
  var probes=new ArrayList<Object>();
  for(var c:input.get("probes")){
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{boolean list=exchange.getRequestURI().getPath().equals("/v1/models")||exchange.getRequestURI().getPath().equals("/api/tags");String field=list?"list":"detail";var spec=c.get(field);int status=spec.get("status").asInt();byte[] body=spec.get("body").asText().getBytes(StandardCharsets.UTF_8);exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(status,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();try{
    String base="http://127.0.0.1:"+server.getAddress().getPort();var svc=new ModelListProbeService(mapper);var protocol=ModelProtocol.parse(c.get("protocol").asText());Object result;
    if(c.get("operation").asText().equals("models"))result=svc.probe(protocol,base,"synthetic-oracle-key",List.of());else result=svc.probeWindow(protocol,base,"synthetic-oracle-key",c.get("model").asText());
    var tree=mapper.valueToTree(result);if(tree.has("latencyMs"))((tools.jackson.databind.node.ObjectNode)tree).put("latencyMs",0);probes.add(tree);
   }finally{server.stop(0);}
  }output.put("probes",probes);
  System.out.println("ORACLE "+mapper.writeValueAsString(output));
 }
}
