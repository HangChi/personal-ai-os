package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.personalos.agent.interaction.service.AgentInteractionService;
import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.ModelConcurrencyGate;
import com.personalos.common.web.TokenBucketRateLimiter;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class RetrievalDegradationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void fallbackKeepsRunAndLeaseUntilDone(boolean missingPort) {
        var completion = mock(ChatCompletionService.class, CALLS_REAL_METHODS);
        ObjectProvider<KnowledgeSearchPort> provider = mock(ObjectProvider.class);
        var port = mock(KnowledgeSearchPort.class);
        when(provider.getIfAvailable()).thenReturn(missingPort ? null : port);
        when(port.retrieve("question", 4)).thenThrow(new IllegalStateException("private upstream detail"));
        ReflectionTestUtils.setField(completion, "knowledgeSearch", provider);
        var repository = mock(ChatRunRepository.class);
        var interactions = mock(AgentInteractionService.class);
        var gate = new ModelConcurrencyGate(1);
        var hub = new RunEventHub();
        var delivery = new RunEventDelivery(hub);
        var codec = new JsonCodec(JsonMapper.builder().build());
        var runs = new ChatRunService(repository, completion, mock(ChatSessionService.class), codec,
                interactions, gate, mock(TokenBucketRateLimiter.class), hub, delivery);
        var events = new ArrayList<String>();
        doAnswer(call -> { events.add(call.getArgument(1)); return null; })
                .when(repository).event(anyString(), anyString(), anyString());
        var output = new AtomicReference<RunEmitter>();
        doAnswer(call -> {
            RunEmitter emitter = call.getArgument(7);
            output.set(emitter);
            List<?> hits = ReflectionTestUtils.invokeMethod(completion, "retrieveForPrompt",
                    "c", "question", 4, true, true, emitter, new AtomicReference<>());
            assertThat(hits).isEmpty();
            return emitter;
        }).when(completion).stream(eq("c"), anyString(), eq("r"), eq(true), eq(4), any(), anyList(), any());
        var request = new StreamChatRequest("question", true, 4, "off", List.of());
        var lease = gate.tryAcquire();
        try {
            ReflectionTestUtils.invokeMethod(runs, "launch",
                    new ChatRunRepository.Run("r", "c", codec.toJson(request), "RUNNING"), false, null, lease);
            assertThat(events).containsExactly("warning", "citations");
            verify(repository, never()).transition(anyString(), anyString(), anyString());
            assertThat(gate.tryAcquire()).as("warning must not release the permit").isNull();
            verify(repository).event(eq("r"), eq("warning"), eq(codec.toJson(new ChatStreamEvents.Warning(
                    "KNOWLEDGE_RETRIEVAL_FAILED", "知识库检索失败，本轮未使用知识库回答"))));
            output.get().publish("delta", new ChatStreamEvents.Delta("fallback answer"));
            output.get().publish("done", new ChatStreamEvents.Done("c", "fallback answer", 1));
            assertThat(events).containsExactly("warning", "citations", "delta", "done");
            verify(repository).transition("r", "RUNNING", "COMPLETED");
            var released = gate.tryAcquire();
            assertThat(released).isNotNull();
            released.close();
        } finally { lease.close(); delivery.close(); }
    }
}
