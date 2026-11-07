package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.WebSource;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.ai.tool.ToolExecutionResult;
import java.io.IOException;
import java.net.http.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class WebSearchToolsBoundaryTest {
 final JsonMapper json=JsonMapper.builder().build();
 WebSearchTools tools(String key) {
  var executor=mock(ToolExecutor.class);
  when(executor.read(anyString(),anyMap(),nullable(ToolContext.class),any())).thenAnswer(i->json.writeValueAsString(((Supplier<?>)i.getArgument(3)).get()));
  return new WebSearchTools(key,executor,json);
 }
 HttpClient client(WebSearchTools tools) {var c=mock(HttpClient.class);ReflectionTestUtils.setField(tools,"client",c);return c;}
 HttpResponse<String> response(int code,String body) {HttpResponse<String> r=mock(HttpResponse.class);when(r.statusCode()).thenReturn(code);when(r.body()).thenReturn(body);return r;}
 @Test void disabledSearchAndToolValidationNeverSendPrivateArguments() {
  var disabled=tools("  ");assertThat(disabled.toolObjects()).isEmpty();assertThat(disabled.lookup("query").failed()).isTrue();
  var t=tools("key");var c=client(t);assertThat(t.toolObjects()).containsExactly(t);
  for(String q:Arrays.asList(null," ","x".repeat(501)))assertThat(json.readTree(t.searchWeb(q,null)).path("status").asText()).isEqualTo("REJECTED");
  assertThat(t.searchWeb("query",null)).contains("无法保存联网引用");assertThat(t.searchWeb("query",new ToolContext(Map.of(ToolContextKeys.WEB_SOURCES,"invalid")))).contains("无法保存联网引用");
  assertThat(t.lookup(" " ).failed()).isTrue();assertThat(t.lookup("字".repeat(501)).failed()).isTrue();verifyNoInteractions(c);
 }
 @Test void lookupBoundsResponsesAndRestoresInterruptWithoutLeakingProviderErrors() throws Exception {
  var t=tools("key");var c=client(t);
  for(var r:List.of(response(200,json.writeValueAsString(Map.of())),response(200,"x".repeat((1<<20)+1)),response(403,"secret"),response(200,"{bad"))) {
   when(c.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(r);
   var got=t.lookup("public query");assertThat(got.sources()).isEmpty();assertThat(got.notice()).doesNotContain("secret");
   assertThat(got.failed()).isEqualTo(r.statusCode()!=200||!r.body().equals("{}"));
  }
  for(Exception ex:List.of(new IOException("secret"),new IllegalStateException("secret"),new InterruptedException("secret"))){
   when(c.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(ex);assertThat(t.lookup("query").failed()).isTrue();assertThat(Thread.interrupted()).isEqualTo(ex instanceof InterruptedException);
  }
 }
 @Test void parsesDeduplicatesBoundsAndRegistersOnlySafeSources() throws Exception {
  var entries=new ArrayList<Object>();
  entries.add(Map.of("url","x".repeat(4097)));entries.add(Map.of("url","file:///private","snippets",List.of("ignore")));entries.add(Map.of("url","https://empty.test","snippets",List.of(1)));
  for(int i=0;i<8;i++){var row=Map.of("url","https://example.test/"+i,"title","Title","snippets",List.of("body",1,"tail"));entries.add(row);entries.add(row);}
  var body=json.writeValueAsString(Map.of("grounding",Map.of("generic",entries)));
  var sources=WebSearchTools.parseSources(body,json);assertThat(sources).hasSize(5);assertThat(sources.getFirst().content()).contains("body","tail").doesNotContain("1");
  var t=tools("key");var c=client(t);var r=response(200,body);when(c.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(r);
  WebSource.Registrar registrar=items->{assertThat(items).hasSize(5);return List.of(new WebSource.Numbered(7,items.getFirst().title(),items.getFirst().url(),items.getFirst().content()));};
  var result=json.readTree(t.searchWeb("query",new ToolContext(Map.of(ToolContextKeys.WEB_SOURCES,registrar))));
  assertThat(result.path("status").asText()).isEqualTo("OK");assertThat(result.path("data").get(0).path("index").asInt()).isEqualTo(7);
  var empty=response(200,"{}");when(c.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(empty);assertThat(t.searchWeb("query",new ToolContext(Map.of(ToolContextKeys.WEB_SOURCES,registrar)))).contains("未返回可引用");
  var failed=response(500,"secret");when(c.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(failed);assertThat(t.searchWeb("query",new ToolContext(Map.of(ToolContextKeys.WEB_SOURCES,registrar)))).contains("ERROR");
  assertThat(t.evidencePrompt(List.of(),true)).contains("需要核对");assertThat(t.evidencePrompt(List.of(),false)).doesNotContain("需要核对");
 }
}
