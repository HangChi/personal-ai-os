package com.personalos.chat.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.chat.dto.*;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.service.*;
import com.personalos.common.ai.*;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChatSessionControllerBoundaryTest {
 @Test void optionalBodiesAndIdempotencyKeysKeepTheirDocumentedSemantics(){
  var sessions=mock(ChatSessionService.class);var completion=mock(ChatCompletionService.class);
  var controller=new ChatSessionController(sessions,mock(ChatMessageService.class),completion,mock(AiProperties.class),mock(ContextBudgetService.class),mock(ChatMarkdownExporter.class),mock(UsageStatsService.class));
  controller.createSession(null);controller.createSession(new CreateSessionRequest("title"));verify(sessions).create(null);verify(sessions).create("title");
  controller.renameSession("c",null);controller.renameSession("c",new RenameSessionRequest("renamed"));verify(sessions).rename("c",null);verify(sessions).rename("c","renamed");
  controller.setModel("c",null);controller.setModel("c",new SetSessionModelRequest("model"));verify(sessions).setModel("c",null);verify(sessions).setModel("c","model");
  var request=new StreamChatRequest("hello",false,null,null,null);
  for(String key:Arrays.asList(null," "," key ","x".repeat(65)))controller.stream("c",key,request);
  var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(completion,times(4)).stream(eq("c"),eq("hello"),keys.capture(),eq(false),anyInt(),any(),anyList());
  assertThat(keys.getAllValues().get(0)).isNotBlank().isNotEqualTo(keys.getAllValues().get(1));assertThat(keys.getAllValues().get(2)).isEqualTo("key");assertThat(keys.getAllValues().get(3)).isEqualTo(JsonCodec.sha256Hex("x".repeat(65)));
 }
 @Test void contextUsesActualTokensWhenAvailableAndValidatesBudgetBeforeMutation(){
  var sessions=mock(ChatSessionService.class);var completion=mock(ChatCompletionService.class);
  var budget=mock(ContextBudgetService.class);var stats=mock(UsageStatsService.class);
  when(budget.modelWindow(any())).thenReturn(new ContextBudgetService.ModelWindow("model",8192,"fixture"));
  when(budget.evidence()).thenReturn(new ContextWindowEvidence());
  var session=mock(ChatSessionEntity.class);when(session.getChatModel()).thenReturn("model");when(sessions.requireSession("c")).thenReturn(session);
  var controller=new ChatSessionController(sessions,mock(ChatMessageService.class),completion,mock(AiProperties.class),budget,mock(ChatMarkdownExporter.class),stats);
  for(Integer tokens:Arrays.asList(null,0,-1))assertThatThrownBy(()->controller.setContextBudget(new ChatSessionController.ContextBudgetRequest(tokens,"c"))).isInstanceOf(BusinessException.class);
  verify(budget,never()).setBudgetTokens(anyInt());
  assertThat(controller.defaultContextUsage("off").data().promptTokens()).isZero();
  when(stats.latestActualPromptTokens("c")).thenReturn(OptionalInt.empty());when(completion.estimatePromptTokens("c")).thenReturn(123);
  assertThat(controller.contextUsage("c","off").data().promptTokens()).isEqualTo(123);
  when(stats.latestActualPromptTokens("c")).thenReturn(OptionalInt.of(456));
  assertThat(controller.contextUsage("c","off").data().estimated()).isFalse();
  for(String id:Arrays.asList(null," ","c"))controller.setContextBudget(new ChatSessionController.ContextBudgetRequest(1024,id));
  verify(budget,times(3)).setBudgetTokens(1024);
 }
}
