package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ContextGuardChatModelTest {
    private static ContextBudgetService budget(int window, int tokens) {
        var service = new ContextBudgetService(new AiProperties(new AiProperties.Memory(20, window), "system"));
        service.registerWindowLookup(name -> window);
        service.setBudgetTokens(tokens);
        return service;
    }
    private static ChatResponse text(String value) { return new ChatResponse(List.of(new Generation(new AssistantMessage(value)))); }
    private static Prompt longHistory() {
        return new Prompt(List.of(new SystemMessage("keep system"), new UserMessage("history ".repeat(3000)),
                new AssistantMessage("old answer"), new UserMessage("current question")),
                ToolCallingChatOptions.builder().model("custom").temperature(0.3).build());
    }
    private static class RecordingModel implements ChatModel {
        final List<Prompt> received = new ArrayList<>();
        RuntimeException error;
        int failures;
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().model("custom").build(); }
        @Override public ChatResponse call(Prompt prompt) {
            received.add(prompt);
            if (failures-- > 0) throw error;
            return text("ok");
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
    }

    @Test
    void trimsFullHistoryTurnsAndReservesActualOutput() {
        var raw = new RecordingModel();
        var budget = budget(8192, 3000);
        new ContextGuardChatModel(raw, budget).call(longHistory());
        Prompt sent = raw.received.get(0);
        assertThat(sent.getInstructions()).extracting(m -> m.getText()).containsExactly("keep system", "current question");
        assertThat(sent.getOptions().getTemperature()).isEqualTo(0.3);
        assertThat(sent.getOptions().getMaxTokens()).isEqualTo(2048);
        assertThat(PromptTokenCounter.count(sent, "custom", 4096) + sent.getOptions().getMaxTokens() + 512).isLessThanOrEqualTo(8192);
    }

    @Test
    void retriesOnlyContextErrorsBeforeAnyStreamOutput() {
        var raw = new RecordingModel(); raw.failures = 1; raw.error = new IllegalStateException("context_length_exceeded");
        var guard = new ContextGuardChatModel(raw, budget(32768, 32768));
        assertThat(guard.stream(longHistory()).blockLast()).isNotNull();
        assertThat(raw.received).hasSize(2);
        assertThat(raw.received.get(1).getInstructions()).hasSizeLessThan(raw.received.get(0).getInstructions().size());
        assertThat(raw.received.get(1).getUserMessage().getText()).isEqualTo("current question");
    }

    @Test
    void neverRetriesAuthenticationFailuresOrPartialOutput() {
        var raw = new RecordingModel(); raw.failures = 2; raw.error = new IllegalStateException("401 unauthorized");
        var guard = new ContextGuardChatModel(raw, budget(32768, 6000));
        assertThatThrownBy(() -> guard.stream(longHistory()).blockLast()).hasMessageContaining("401");
        assertThat(raw.received).hasSize(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel partial = new RecordingModel() {
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.concat(Flux.just(text("started")), Flux.error(new IllegalStateException("maximum context length")));
            }
        };
        assertThatThrownBy(() -> new ContextGuardChatModel(partial, budget(32768, 6000)).stream(longHistory()).blockLast())
                .isInstanceOf(BusinessException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsOversizedCurrentInputWithoutCallingModelOrTruncatingIt() {
        var raw = new RecordingModel();
        var prompt = new Prompt(List.of(new SystemMessage("attachment ".repeat(10000)), new UserMessage("keep this")), raw.getOptions());
        assertThatThrownBy(() -> new ContextGuardChatModel(raw, budget(8192, 8192)).call(prompt))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONTEXT_TOO_LARGE);
        assertThat(raw.received).isEmpty();
        assertThat(prompt.getSystemMessage().getText()).hasSize(110000);
    }

    @Test
    void tokenCounterIncludesSchemasToolArgumentsAndResults() {
        var raw = new RecordingModel();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("query").description("schema ".repeat(100)).inputSchema("{\"type\":\"object\"}").build(); }
            @Override public String call(String args) { return "result"; }
        };
        Prompt base = new Prompt(new UserMessage("hello"), raw.getOptions());
        Prompt full = new Prompt(List.of(new UserMessage("hello"),
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "query", "argument ".repeat(100)))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "query", "result ".repeat(100)))).build()),
                ToolCallingChatOptions.builder().model("custom").toolCallbacks(tool).build());
        assertThat(PromptTokenCounter.count(full, "custom", 4096)).isGreaterThan(PromptTokenCounter.count(base, "custom", 4096) + 300);
    }

    @Test
    void boundsToolResultsWithoutBreakingCallIds() {
        var raw = new RecordingModel();
        var original = new Prompt(List.of(new UserMessage("current"),
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "query", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "query", "payload ".repeat(12000)))).build()), raw.getOptions());
        new ContextGuardChatModel(raw, budget(8192, 8192)).call(original);
        var response = (ToolResponseMessage) raw.received.get(0).getInstructions().get(2);
        assertThat(response.getResponses().get(0).id()).isEqualTo("id");
        assertThat(response.getResponses().get(0).responseData()).contains("上下文预算截断");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retryInsideToolLoopDoesNotReplayCompletedTool(boolean streaming) {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("save").description("save once").inputSchema("{\"type\":\"object\"}").build(); }
            @Override public String call(String args) { executions.incrementAndGet(); return "saved"; }
        };
        AtomicInteger requests = new AtomicInteger();
        ChatModel raw = new RecordingModel() {
            @Override public ChatResponse call(Prompt prompt) {
                int round = requests.getAndIncrement();
                if (round == 0) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("save-id", "function", "save", "{}"))).build())));
                if (round == 1) throw new IllegalStateException("context_length_exceeded");
                return text("done");
            }
        };
        var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add("turn", longHistory().getInstructions().subList(1, 3));
        var client = ChatClient.builder(new ContextGuardChatModel(raw, budget(32768, 6000)))
                .defaultAdvisors(org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(memory).build(),
                        ToolCallingAdvisor.builder().build()).defaultTools(tool).build();
        var request = client.prompt().system("keep system").user("current question")
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "turn"));
        String answer = streaming ? request.stream().content().blockLast() : request.call().content();
        assertThat(answer).isEqualTo("done");
        assertThat(executions).hasValue(1);
        assertThat(requests).hasValue(3);
        assertThat(memory.get("turn").stream().filter(m -> m instanceof UserMessage && "current question".equals(m.getText())).count()).isEqualTo(1);
    }
    @Test
    void retryExhaustionReturnsSpecificErrorAndRemainsBounded() {
        var raw = new RecordingModel(); raw.failures = 10;
        raw.error = new IllegalStateException("maximum context length");
        assertThatThrownBy(() -> new ContextGuardChatModel(raw, budget(32768, 6000)).call(longHistory()))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONTEXT_TOO_LARGE);
        assertThat(raw.received).hasSize(2);
    }

    @Test
    void cancellationStopsUpstreamWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ChatModel raw = new RecordingModel() {
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.<ChatResponse>never().doOnCancel(cancelled::incrementAndGet);
            }
        };
        var subscription = new ContextGuardChatModel(raw, budget(32768, 6000)).stream(longHistory()).subscribe();
        subscription.dispose();
        assertThat(calls).hasValue(1);
        assertThat(cancelled).hasValue(1);
    }
    @Test
    void mediaAllowanceAndReasoningTokensAreReserved() {
        var raw = new RecordingModel();
        var image = new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}));
        var user = UserMessage.builder().text("describe").media(image).build();
        var options = org.springframework.ai.openai.OpenAiChatOptions.builder().model("custom")
                .maxCompletionTokens(10000).extraBody(java.util.Map.of("thinking_budget", 8192)).build();
        new ContextGuardChatModel(raw, budget(32768, 32768)).call(new Prompt(user, options));
        var sent = raw.received.get(0);
        assertThat(((UserMessage) sent.getInstructions().get(0)).getMedia()).hasSize(1);
        assertThat(((org.springframework.ai.openai.OpenAiChatOptions) sent.getOptions()).getMaxCompletionTokens()).isEqualTo(9216);
        assertThat(PromptTokenCounter.count(sent, "custom", 4096)).isGreaterThanOrEqualTo(4096);
    }
}
