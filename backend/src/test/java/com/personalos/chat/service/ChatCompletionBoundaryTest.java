package com.personalos.chat.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.chat.dto.*;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.metrics.ChatStreamMetrics;
import com.personalos.chat.support.SseHeartbeatScheduler;
import com.personalos.common.ai.*;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.RagResult;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class ChatCompletionBoundaryTest {
    private static ChatResponse text(String value) {return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));}
    private static KnowledgeHitDto hit(UUID id) {return new KnowledgeHitDto(id,"资料","a.md",0,"可核对的资料",0.8);}
    static class Fixture {
        final ChatModel model=mock(ChatModel.class);
        final AiChatClientProvider provider=mock(AiChatClientProvider.class);
        final ChatSessionService sessions=mock(ChatSessionService.class);
        final ChatSessionEntity session=mock(ChatSessionEntity.class);
        final ChatPromptAssembler prompts=mock(ChatPromptAssembler.class);
        final ObjectProvider<KnowledgeSearchPort> ports=mock(ObjectProvider.class);
        final ChatTurnFinisher finisher=mock(ChatTurnFinisher.class);
        final ChatStreamMetrics metrics=mock(ChatStreamMetrics.class);
        final SseHeartbeatScheduler clock=mock(SseHeartbeatScheduler.class);
        final TokenUsageRecorder usage=mock(TokenUsageRecorder.class);
        final WebSearchTools web=mock(WebSearchTools.class);
        final ChatAttachmentContextBuilder attachments=mock(ChatAttachmentContextBuilder.class);
        final ChatFailureRecovery recovery=mock(ChatFailureRecovery.class);
        final AtomicReference<Runnable> watchdog=new AtomicReference<>();
        final CountDownLatch entered=new CountDownLatch(1),terminal=new CountDownLatch(1);
        final List<String> events=new CopyOnWriteArrayList<>();
        final AtomicReference<Prompt> prompt=new AtomicReference<>();
        final ChatCompletionService service;
        final RunEmitter emitter=new RunEmitter((name,payload)->{events.add(name);if(List.of("done","error","yield").contains(name))terminal.countDown();});
        Fixture(Flux<ChatResponse> responses) {
            when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().model("fixture").build());
            when(model.stream(any(Prompt.class))).thenAnswer(call->{prompt.set(call.getArgument(0));entered.countDown();return responses;});
            var client=ChatClient.create(model);var target=new AiChatClientProvider.ChatTarget(model,client,client);
            when(provider.targetFor(any())).thenReturn(target);when(provider.summaryChatClient(target)).thenReturn(client);
            when(provider.applyThinkingOptions(any(),anyInt(),same(model))).thenAnswer(call->call.getArgument(0));
            when(sessions.requireSession("c")).thenReturn(session);
            when(prompts.prepare(anyList(),anyString(),anyString(),anyString(),anyInt(),any(),anyInt())).thenReturn("系统提示");
            when(clock.scheduleOnce(any(),anyLong())).thenAnswer(call->{watchdog.set(call.getArgument(0));return mock(ScheduledFuture.class);});
            when(attachments.build(any())).thenReturn(ChatAttachmentContext.empty());
            service=new ChatCompletionService(provider,sessions,prompts,ports,finisher,metrics,clock,attachments,Duration.ofSeconds(3),usage,new AgentExecutionProperties(16, 32),web,recovery);
        }
        void start(boolean knowledge,String query){service.stream("c",query,"turn",knowledge,4,ThinkingLevel.OFF,List.of(),emitter);}
        void await() throws Exception {assertThat(terminal.await(3,TimeUnit.SECONDS)).isTrue();}
        void entered() throws Exception {assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void readOnlyAndConcurrentTurnsAreRejected() throws Exception {
        var f=new Fixture(Flux.never());when(f.session.isReadOnlyReport()).thenReturn(true);
        assertThatThrownBy(()->f.start(false,"问")).isInstanceOf(BusinessException.class);verifyNoInteractions(f.provider);
        when(f.session.isReadOnlyReport()).thenReturn(false);f.start(false,"问");f.entered();
        assertThatThrownBy(()->f.start(false,"第二轮")).isInstanceOf(BusinessException.class);
        f.emitter.cancel();f.await();verify(f.metrics).streamClosed();
    }
    @Test void recoveryPublishesWarningAndSkipsPostTurnActions() throws Exception {
        var f=new Fixture(Flux.concat(Flux.just(text("已有草稿")),Flux.error(new IOException("private"))));
        when(f.recovery.recover(any(),anyString(),anyString(),anyString(),any())).thenReturn(Flux.just(text("恢复结果")));
        f.emitter.recoveryContext("恢复上下文");f.start(false,"问");f.await();
        assertThat(f.events).containsSubsequence("start","delta","warning","delta","done");assertThat(f.emitter.recovered()).isTrue();
        assertThat(f.prompt.get().getContents()).contains("恢复上下文");
        verify(f.finisher,timeout(1000)).recordYieldedReply(eq("c"),eq("已有草稿恢复结果"),anyList(),anyList());
        verify(f.finisher,never()).afterDone(anyString(),anyString(),anyString());
        verify(f.recovery).recover(any(),eq("turn"),eq("问"),eq("已有草稿"),eq(ErrorCode.AI_UPSTREAM_ERROR));
    }
    @Test void summaryOnlyNeverRunsToolsOrRecovers() throws Exception {
        var tool=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("t","function","write","{}"))).build();
        var f=new Fixture(Flux.just(new ChatResponse(List.of(new Generation(tool)))));f.emitter.summaryOnly(true);f.start(false,"问");f.await();
        assertThat(f.events).contains("error").doesNotContain("done");verifyNoInteractions(f.recovery);
        verify(f.finisher,never()).afterDone(anyString(),anyString(),anyString());
    }
    @Test void controlSignalYieldsWithoutError() throws Exception {
        var f=new Fixture(Flux.error(new AgentControlSignal()));f.start(false,"问");f.await();
        assertThat(f.events).contains("yield").doesNotContain("error","done");
        verify(f.finisher).recordYieldedReply(eq("c"),eq(""),anyList(),anyList());verifyNoInteractions(f.recovery);
    }
    @Test void timeoutUsesDeterministicClosingExactlyOnce() throws Exception {
        var f=new Fixture(Flux.concat(Flux.just(text("部分")),Flux.never()));when(f.recovery.deterministic("turn")).thenReturn("已保存回执");
        f.start(false,"问");f.entered();verify(f.metrics,timeout(1000)).firstContent(anyString(),any(),anyLong());
        f.watchdog.get().run();f.await();f.watchdog.get().run();f.emitter.cancel();
        assertThat(f.events).containsSubsequence("delta","warning","delta","done");assertThat(Collections.frequency(f.events,"done")).isEqualTo(1);
        verify(f.finisher).recordYieldedReply(eq("c"),eq("部分\n\n已保存回执"),anyList(),anyList());verify(f.metrics).streamClosed();
    }
    @Test void cancellationBeforeRegistrationAndDuringStreamingCannotRecover() throws Exception {
        for(boolean early:List.of(false,true)){
            var f=new Fixture(Flux.never());if(early)f.emitter.cancel();f.start(false,"问");
            if(!early){f.entered();f.emitter.cancel();}f.await();f.emitter.cancel();
            verifyNoInteractions(f.recovery);verify(f.finisher).recordInterruptedReply(eq("c"),anyString(),eq(ErrorCode.INTERNAL_ERROR),anyList(),anyList());
            verify(f.metrics).streamClosed();
        }
    }
    @Test void knowledgeFailuresDegradeAndComplete() throws Exception {
        for(boolean missing:List.of(false,true)){
            var f=new Fixture(Flux.just(text("正常回答")));
            if(!missing){var port=mock(KnowledgeSearchPort.class);when(f.ports.getIfAvailable()).thenReturn(port);when(port.retrieveForConversation(anyString(),anyInt(),anyList())).thenThrow(new IllegalStateException("private"));}
            f.start(true,"问题");f.await();assertThat(f.events).containsSubsequence("start","warning","citations","delta","done");
            verify(f.finisher,timeout(1000)).afterDone("c","问题","正常回答");
        }
    }
    @Test void guardedKnowledgeUsesServerAnswerUnlessWebEvidenceExists() throws Exception {
        for(boolean withWeb:List.of(false,true)){
            var f=new Fixture(Flux.just(text("网页回答")));var port=mock(KnowledgeSearchPort.class);when(f.ports.getIfAvailable()).thenReturn(port);
            when(port.retrieveForConversation(anyString(),anyInt(),anyList())).thenReturn(new RagResult(List.of(hit(null)),List.of(),List.of(),List.of(),"CLARIFY",List.of(),"请指定范围"));
            when(f.web.lookup(anyString())).thenReturn(new WebSearchTools.SearchOutcome(withWeb?List.of(WebSource.from("网页","https://example.test","证据")):List.of(),"查询提示",false));
            when(f.web.evidencePrompt(anyList(),anyBoolean())).thenReturn("网页证据区块");f.start(true,"联网搜索：测试");f.await();
            if(withWeb){assertThat(f.prompt.get().getContents()).contains("网页证据区块");verify(f.finisher).attachCitations(eq("c"),eq(List.of()),anyList());}
            else{verify(f.finisher).recordGuardedReply(eq("c"),anyString(),contains("查询提示"));verify(f.model,never()).stream(any(Prompt.class));}
            verify(f.finisher,never()).afterDone(anyString(),anyString(),anyString());
        }
    }
    @Test void webFailureNoticePublishesKnowledgeCitations() throws Exception {
        var f=new Fixture(Flux.just(text("答")));var port=mock(KnowledgeSearchPort.class);when(f.ports.getIfAvailable()).thenReturn(port);
        when(port.retrieveForConversation(anyString(),anyInt(),anyList())).thenReturn(RagResult.legacy(List.of(hit(UUID.randomUUID())),"问"));
        when(f.web.lookup(anyString())).thenReturn(new WebSearchTools.SearchOutcome(List.of(),"联网失败",true));f.start(true,"联网搜索：测试");f.await();
        assertThat(f.events).contains("citations");assertThat(f.prompt.get().getContents()).contains("本轮没有联网证据");
    }
    @Test void toolRegistrarsPublishOnlyWhileTurnIsActive() throws Exception {
        var f=new Fixture(Flux.never());f.start(false,"问");f.entered();var context=((ToolCallingChatOptions)f.prompt.get().getOptions()).getToolContext();
        var web=(WebSource.Registrar)context.get(ToolContextKeys.WEB_SOURCES);
        var kb=(com.personalos.knowledge.tool.KnowledgeTools.RegisterSources)context.get(ToolContextKeys.KNOWLEDGE_SOURCES);
        var progress=(com.personalos.common.ai.tool.ToolProgress)context.get(ToolContextKeys.PROGRESS);
        var check=(Runnable)context.get(ToolContextKeys.CONTROL);var checkpoints=new AtomicInteger();f.emitter.control(checkpoints::incrementAndGet);check.run();assertThat(checkpoints).hasValue(1);
        assertThat(web.add(List.of(WebSource.from("来源","https://example.test","摘录")))).hasSize(1);assertThat(kb.add(List.of(hit(UUID.randomUUID())))).hasSize(1);
        progress.publish("id","tool","RUNNING",2L);assertThat(f.events).contains("activity","citations");f.emitter.cancel();f.await();int count=f.events.size();
        assertThat(web.add(List.of())).isEmpty();assertThat(kb.add(List.of())).isEmpty();progress.publish("id","tool","DONE",3L);assertThat(f.events).hasSize(count);
    }
    @Test void foregroundFallbackCallbacksCleanUpOnce() throws Exception {
        for(String callback:List.of("timeoutCallback","errorCallback","completionCallback")){
            var f=new Fixture(Flux.never());SseEmitter emitter=f.service.stream("c","问","t",false,4,ThinkingLevel.DEEP,List.of());f.entered();
            Object hook=ReflectionTestUtils.getField(emitter,callback);
            if(callback.equals("errorCallback"))((java.util.function.Consumer<Throwable>)hook).accept(new IOException("socket"));else((Runnable)hook).run();
            verify(f.metrics,timeout(1000)).streamClosed();f.watchdog.get().run();verify(f.metrics).streamClosed();
        }
    }
    @Test void classificationAndEstimatesKeepPublicContract(){
        var f=new Fixture(Flux.empty());
        for(Throwable cause:List.of(new IOException(),new org.springframework.web.client.RestClientException("private"),new BusinessException(ErrorCode.VALIDATION_FAILED),new IllegalStateException())){
            ErrorCode actual=ReflectionTestUtils.invokeMethod(f.service,"classify",new RuntimeException(cause));
            assertThat(actual).isEqualTo(cause instanceof BusinessException?ErrorCode.VALIDATION_FAILED:cause instanceof IllegalStateException?ErrorCode.INTERNAL_ERROR:ErrorCode.AI_UPSTREAM_ERROR);
        }
        Throwable cycle=new Throwable(){@Override public synchronized Throwable getCause(){return this;}};
        assertThat((ErrorCode)ReflectionTestUtils.invokeMethod(f.service,"classify",cycle)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        when(f.prompts.estimatePromptChars("c")).thenReturn(160);assertThat(f.service.estimatePromptChars("c")).isEqualTo(160);assertThat(f.service.estimatePromptTokens("c")).isPositive();
        assertThat((Object)ReflectionTestUtils.invokeMethod(f.service,"resolveUsage",null,"",0,0,null)).isNotNull();
    }
    @Test void summarySuccessImagesAndProviderUsageArePreserved() throws Exception {
        var metadata=org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("actual-model").usage(new org.springframework.ai.chat.metadata.DefaultUsage(10,4)).build();
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))),metadata);
        var f=new Fixture(Flux.just(response));f.emitter.summaryOnly(true);
        f.service.stream("c","问","",false,4,ThinkingLevel.OFF,List.of(),f.emitter);f.await();
        assertThat(f.events).contains("done");verify(f.finisher,never()).afterDone(anyString(),anyString(),anyString());
        var noOptions=new Fixture(Flux.empty());when(noOptions.model.getOptions()).thenReturn(null);noOptions.start(false,"问");noOptions.await();assertThat(noOptions.events).contains("error");
        var image=new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,new org.springframework.core.io.ByteArrayResource(new byte[]{1,2}));
        var withImage=new Fixture(Flux.just(text("图像说明")));when(withImage.attachments.build(any())).thenReturn(new ChatAttachmentContext(List.of(image),"附件"));
        withImage.start(false,"图里是什么");withImage.await();
        assertThat(withImage.prompt.get().getUserMessage().getMedia()).hasSize(1);
        var actual=com.personalos.chat.support.StreamUsage.estimated("m","q",10,"a",2);
        assertThat((Object)ReflectionTestUtils.invokeMethod(f.service,"resolveUsage",actual,"q",10,2,"a")).isSameAs(actual);
        var calls=new AtomicInteger();ReflectionTestUtils.invokeMethod(f.service,"runWithRequestId"," ",(Runnable)calls::incrementAndGet);assertThat(calls).hasValue(1);
        for(Throwable error:List.of(new org.springframework.web.reactive.function.client.WebClientRequestException(new IOException(),org.springframework.http.HttpMethod.GET,java.net.URI.create("https://fixture.invalid"),new org.springframework.http.HttpHeaders()),new org.springframework.ai.retry.NonTransientAiException("private"))){
            assertThat((ErrorCode)ReflectionTestUtils.invokeMethod(f.service,"classify",error)).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }
}
