package com.personalos.memory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.*;
import com.personalos.common.json.JsonCodec;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

class AutoMemoryBoundaryTest {
 @Test void disabledAndIncompleteTurnsNeverScheduleModelWork(){
  var memory=mock(MemoryService.class);var provider=mock(AiChatClientProvider.class);var pool=mock(ExecutorService.class);
  var codec=new JsonCodec(new ObjectMapper());
  new AutoMemoryService(memory,provider,new ContextProperties(null,null),codec,pool).afterTurn("c","user","reply");
  var service=new AutoMemoryService(memory,provider,new ContextProperties(null,new ContextProperties.AutoMemory(true,3)),codec,pool);
  for(String user:Arrays.asList(null,"","user"))for(String reply:Arrays.asList(null," ","reply"))if(user==null||user.isBlank()||reply==null||reply.isBlank())service.captureAsync(user,reply);
  verifyNoInteractions(pool,provider,memory);
  doThrow(new RejectedExecutionException()).when(pool).execute(any());
  assertThatCode(()->service.captureAsync("user","reply")).doesNotThrowAnyException();verifyNoInteractions(provider,memory);
 }
 @Test void captureContinuesAfterOneRejectedFactAndIgnoresModelFailure(){
  var memory=mock(MemoryService.class);var provider=mock(AiChatClientProvider.class);
  var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
  when(provider.requirePlainChatClient()).thenReturn(client);
  var call=client.prompt().system(anyString()).user(anyString()).call();
  var service=new AutoMemoryService(memory,provider,new ContextProperties(null,new ContextProperties.AutoMemory(true,3)),new JsonCodec(new ObjectMapper()),mock(ExecutorService.class));
  when(call.content()).thenReturn("[\"用户长期使用中文沟通\",\"用户日常使用Linux系统\"]");
  doThrow(new IllegalStateException("rejected")).when(memory).createAuto("用户长期使用中文沟通");
  service.capture("user","reply");
  verify(memory).createAuto("用户长期使用中文沟通");verify(memory).createAuto("用户日常使用Linux系统");
  clearInvocations(memory);when(call.content()).thenReturn("[]");service.capture("user","reply");verifyNoInteractions(memory);
  when(provider.requirePlainChatClient()).thenThrow(new IllegalStateException("offline"));
  assertThatCode(()->service.capture("user","reply")).doesNotThrowAnyException();verifyNoInteractions(memory);
 }
 @Test void factParserIgnoresMissingArraysNullRowsAndDisabledLimits(){
  var codec=mock(JsonCodec.class);
  assertThat(MemoryFactParser.parse(codec,null,2)).isEmpty();assertThat(MemoryFactParser.parse(codec,"[]",0)).isEmpty();
  when(codec.fromJson("[]",String[].class)).thenReturn(null);assertThat(MemoryFactParser.parse(codec,"[]",2)).isEmpty();
  when(codec.fromJson("[]",String[].class)).thenReturn(new String[]{null,"  valid memory  "});assertThat(MemoryFactParser.parse(codec,"[]",2)).containsExactly("valid memory");
 }
}
