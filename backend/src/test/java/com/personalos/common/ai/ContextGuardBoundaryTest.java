package com.personalos.common.ai;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
class ContextGuardBoundaryTest {
 ContextBudgetService budget(){var b=new ContextBudgetService(new AiProperties(new AiProperties.Memory(20,8192),"system"));b.registerWindowLookup(name->8192);b.setBudgetTokens(6000);return b;}
 @Test void optionalProviderOptionsAndUsageAreHandledWithoutInventingEvidence(){
  for(Integer max:Arrays.asList(null,0,-1,512))for(Object thinking:Arrays.asList(null,0,-1,"invalid",256)){
   var raw=mock(ChatModel.class);var b=budget();var g=new ContextGuardChatModel(raw,b);when(raw.call(any(Prompt.class))).thenReturn(null);
   var options=OpenAiChatOptions.builder().model("m").maxTokens(max).extraBody(thinking==null?null:Map.of("thinking_budget",thinking)).build();
   g.call(new Prompt(new UserMessage("query"),options));var sent=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(raw).call(sent.capture());assertThat(sent.getValue().getOptions().getMaxTokens()).isGreaterThan(0);assertThat(b.evidence().snapshot("m").verifiedInputTokens()).isZero();
  }
  var raw=mock(ChatModel.class);var g=new ContextGuardChatModel(raw,budget());when(raw.call(any(Prompt.class))).thenReturn(null);assertThat(g.getOptions()).isNull();g.call(new Prompt(List.of(new UserMessage("query")),(ChatOptions)null));
  var response=mock(ChatResponse.class);when(response.getMetadata()).thenReturn(null);when(raw.call(any(Prompt.class))).thenReturn(response);g.call(new Prompt("query"));
  var metadata=mock(ChatResponseMetadata.class);when(response.getMetadata()).thenReturn(metadata);g.call(new Prompt("query"));
  var usage=mock(Usage.class);when(metadata.getUsage()).thenReturn(usage);when(usage.getPromptTokens()).thenReturn(null);g.call(new Prompt("query"));
 }
 @Test void providerCompletionLimitsAndThinkingReserveRejectUnsafeRequests(){
  var raw=mock(ChatModel.class);var g=new ContextGuardChatModel(raw,budget());
  for(Integer max:Arrays.asList(0,-1,512)){var p=g.prepare(new Prompt(new UserMessage("query"),OpenAiChatOptions.builder().maxCompletionTokens(max).build()),0);assertThat(((OpenAiChatOptions)p.getOptions()).getMaxCompletionTokens()).isGreaterThan(0);}
  assertThatThrownBy(()->g.call(new Prompt(new UserMessage("query"),OpenAiChatOptions.builder().extraBody(Map.of("thinking_budget",8192)).build()))).hasMessageContaining("上下文");verify(raw,never()).call(any(Prompt.class));
 }
 @Test void syncAndStreamFailuresDoNotRetryWhenPromptCannotShrink(){
  for(boolean stream:new boolean[]{false,true}){var raw=mock(ChatModel.class);when(raw.getOptions()).thenReturn(ChatOptions.builder().model("m").build());when(raw.call(any(Prompt.class))).thenThrow(new IllegalStateException("context_length_exceeded"));when(raw.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("context_length_exceeded")));var g=new ContextGuardChatModel(raw,budget());var prompt=new Prompt("short");assertThatThrownBy(()->{if(stream)g.stream(prompt).blockLast();else g.call(prompt);}).hasMessageContaining("上下文");if(stream)verify(raw).stream(any(Prompt.class));else verify(raw).call(any(Prompt.class));}
 }

 @Test void ordinaryFailuresAndAlreadyEmittedOverflowNeverReplayModelCalls(){
  var raw=mock(ChatModel.class);var guard=new ContextGuardChatModel(raw,budget());var failure=new IllegalStateException("other failure");when(raw.call(any(Prompt.class))).thenThrow(failure);
  assertThatThrownBy(()->guard.call(new Prompt("query"))).isSameAs(failure);
  when(raw.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(new ChatResponse(List.of())),Flux.error(new IllegalStateException("context_length_exceeded"))));
  assertThatThrownBy(()->guard.stream(new Prompt("query")).blockLast()).hasMessageContaining("上下文");verify(raw).stream(any(Prompt.class));
 }
 @Test void pruningKeepsInterleavedSystemAndShortOrNullToolResults(){
  var raw=mock(ChatModel.class);var guard=new ContextGuardChatModel(raw,budget());
  var responses=java.util.Arrays.asList(new ToolResponseMessage.ToolResponse("a","tool",null),new ToolResponseMessage.ToolResponse("b","tool","small"));
  var messages=new java.util.ArrayList<Message>(List.of(new UserMessage("old"),new SystemMessage("rules"),new AssistantMessage("answer"),new UserMessage("new"),ToolResponseMessage.builder().responses(responses).build()));
  assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(ContextGuardChatModel.class,"removeOldestTurn",messages)).isTrue();
  assertThat(messages).hasSize(3);assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
  assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(ContextGuardChatModel.class,"shortenToolResults",messages,64)).isFalse();
 }
}
