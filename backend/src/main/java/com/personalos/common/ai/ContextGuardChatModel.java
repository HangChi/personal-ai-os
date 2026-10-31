package com.personalos.common.ai;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** Innermost model boundary: checks every tool round without replaying the memory/tool advisors. */
public final class ContextGuardChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ContextBudgetService budget;
    private final long evidenceGeneration;

    public ContextGuardChatModel(ChatModel delegate, ContextBudgetService budget) {
        this.delegate = delegate;
        this.budget = budget;
        this.evidenceGeneration = budget.evidence().generation();
    }

    @Override
    public ChatOptions getOptions() { return delegate.getOptions(); }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt previous = null;
        for (int attempt = 0; ; attempt++) {
            Prompt prepared = prepare(prompt, attempt, previous == null ? Integer.MAX_VALUE : Math.max(1, (int) (count(previous) * 0.65)));
            if (previous != null && count(prepared) >= count(previous)) throw tooLarge();
            try {
                ChatResponse response = delegate.call(prepared);
                recordAccepted(prepared, response);
                return response;
            } catch (RuntimeException error) {
                if (!isContextOverflow(error)) throw error;
                budget.evidence().rejected(evidenceGeneration, model(prepared), error, count(prepared));
                if (attempt >= budget.protection().maxRetries()) throw tooLarge();
                previous = prepared;
            }
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamAttempt(prompt, 0, null);
    }

    private Flux<ChatResponse> streamAttempt(Prompt prompt, int attempt, Prompt previous) {
        return Flux.defer(() -> {
            Prompt prepared = prepare(prompt, attempt, previous == null ? Integer.MAX_VALUE : Math.max(1, (int) (count(previous) * 0.65)));
            if (previous != null && count(prepared) >= count(previous)) return Flux.error(tooLarge());
            AtomicBoolean emitted = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<ChatResponse> usage = new java.util.concurrent.atomic.AtomicReference<>();
            return Flux.defer(() -> delegate.stream(prepared))
                    .doOnNext(response -> {
                        emitted.set(true);
                        if (actualInputTokens(response) > 0) usage.set(response);
                    })
                    .doOnComplete(() -> recordAccepted(prepared, usage.get()))
                    .onErrorResume(error -> {
                        if (!isContextOverflow(error)) return Flux.error(error);
                        budget.evidence().rejected(evidenceGeneration, model(prepared), error, count(prepared));
                        // Never restart after any response chunk: text or tool calls may already
                        // have reached the caller. Only the failed raw model request is retried.
                        if (emitted.get() || attempt >= budget.protection().maxRetries()) return Flux.error(tooLarge());
                        return streamAttempt(prompt, attempt + 1, prepared);
                    });
        });
    }

    private String model(Prompt prompt) {
        String name = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        if (name == null && delegate.getOptions() != null) name = delegate.getOptions().getModel();
        return budget.effectiveModelName(name);
    }

    private int count(Prompt prompt) {
        int estimate = baseCount(prompt);
        double ratio = budget.evidence().snapshot(evidenceGeneration, model(prompt)).tokenRatio();
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(estimate * ratio));
    }

    private int baseCount(Prompt prompt) {
        return PromptTokenCounter.count(prompt, model(prompt), budget.protection().imageReserveTokens());
    }

    private static int actualInputTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) return 0;
        Integer tokens = response.getMetadata().getUsage().getPromptTokens();
        return tokens == null ? 0 : tokens;
    }

    private void recordAccepted(Prompt prompt, ChatResponse response) {
        // Image allowance error says nothing about the text tokenizer; retain only verified usage.
        boolean hasMedia = prompt.getInstructions().stream().anyMatch(message ->
                message instanceof org.springframework.ai.content.MediaContent media && !media.getMedia().isEmpty());
        budget.evidence().accepted(evidenceGeneration, model(prompt), hasMedia ? 0 : baseCount(prompt), actualInputTokens(response));
    }

    /** Retains system instructions, the current user message and complete tool call/result pairs. */
    Prompt prepare(Prompt original, int attempt) {
        return prepare(original, attempt, Integer.MAX_VALUE);
    }

    private Prompt prepare(Prompt original, int attempt, int retryInputLimit) {
        String model = model(original);
        int window = budget.contextWindowTokens(model);
        ChatOptions options = original.getOptions() == null ? delegate.getOptions() : original.getOptions();
        if (options == null) options = ChatOptions.builder().build();
        int output = budget.outputReserveForWindow(window);
        if (options.getMaxTokens() != null && options.getMaxTokens() > 0) output = Math.min(output, options.getMaxTokens());
        if (options instanceof OpenAiChatOptions openAi) {
            if (openAi.getMaxCompletionTokens() != null && openAi.getMaxCompletionTokens() > 0) {
                output = Math.min(output, openAi.getMaxCompletionTokens());
            }
            Object thinking = openAi.getExtraBody() == null ? null : openAi.getExtraBody().get("thinking_budget");
            if (thinking instanceof Number number && number.intValue() > 0) {
                output = Math.max(output, number.intValue() + 1024);
                if (output + budget.safetyMarginForWindow(window) >= window) throw tooLarge();
            }
        }
        int cap = Math.min(budget.budgetTokensFor(model), budget.inputLimitForModel(model, output));
        cap = Math.min(retryInputLimit, Math.max(1, (int) (cap * Math.pow(0.65, attempt))));
        // Preserve provider-specific settings and tool callbacks/context through mutate().
        ChatOptions protectedOptions = options.mutate().maxTokens(output).build();
        if (options instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
            protectedOptions = openAi.mutate().maxTokens(null).maxCompletionTokens(output).build();
        }
        List<Message> messages = new ArrayList<>(original.getInstructions());
        Prompt candidate = new Prompt(messages, protectedOptions);
        while (count(candidate) > cap && removeOldestTurn(messages)) candidate = new Prompt(messages, protectedOptions);
        // Large tool payloads are bounded without executing the tool again; IDs and names remain
        // intact and truncation is explicit. User input and attachments are never silently cut.
        int toolChars = Math.max(64, cap / 2);
        while (count(candidate) > cap && shortenToolResults(messages, toolChars)) {
            candidate = new Prompt(messages, protectedOptions);
            if (toolChars == 64) break;
            toolChars = Math.max(64, toolChars / 2);
        }
        if (count(candidate) > cap) throw tooLarge();
        return candidate;
    }

    private static boolean removeOldestTurn(List<Message> messages) {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).getMessageType() == MessageType.USER) lastUser = i;
        int first = -1;
        for (int i = 0; i < lastUser; i++) if (messages.get(i).getMessageType() != MessageType.SYSTEM) { first = i; break; }
        if (first < 0) return false;
        int end = first + 1;
        while (end < lastUser && messages.get(end).getMessageType() != MessageType.USER) end++;
        for (int i = end - 1; i >= first; i--) if (messages.get(i).getMessageType() != MessageType.SYSTEM) messages.remove(i);
        return true;
    }

    private static boolean shortenToolResults(List<Message> messages, int maxChars) {
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage tool)) continue;
            var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
            boolean shortened = false;
            for (var response : tool.getResponses()) {
                String text = response.responseData();
                if (text != null && text.length() > maxChars) {
                    text = text.substring(0, maxChars) + "\n[工具结果因上下文预算截断，请按需缩小范围重新查询]";
                    shortened = true;
                }
                responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), text));
            }
            if (shortened) {
                messages.set(i, ToolResponseMessage.builder().responses(responses).metadata(tool.getMetadata()).build());
                changed = true;
            }
        }
        return changed;
    }

    static boolean isContextOverflow(Throwable error) {
        return ContextWindowEvidence.isOverflow(error);
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.CONTEXT_TOO_LARGE);
    }
}
