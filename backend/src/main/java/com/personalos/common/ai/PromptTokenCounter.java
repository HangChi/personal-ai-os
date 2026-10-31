package com.personalos.common.ai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Counts the actual assembled prompt, including schemas, tool exchanges and media allowances. */
public final class PromptTokenCounter {
    private static final EncodingRegistry ENCODINGS = Encodings.newLazyEncodingRegistry();

    private PromptTokenCounter() { }

    public static int text(String text, String model) {
        if (text == null || text.isEmpty()) return 0;
        Encoding encoding = model == null ? null : ENCODINGS.getEncodingForModel(model).orElse(null);
        if (encoding != null) return encoding.countTokensOrdinary(text);
        // A provider's tokenizer cannot be inferred from an arbitrary alias. Use a conservative
        // local estimate and keep the safety margin; never describe this as exact provider usage.
        int bpe = ENCODINGS.getEncoding(EncodingType.CL100K_BASE).countTokensOrdinary(text);
        return Math.max(bpe, ContextBudgetService.toTokensByContent(text));
    }

    public static int message(Message message, String model, int imageReserve) {
        long total = 12L + text(message.getText(), model);
        if (message instanceof AssistantMessage assistant) {
            for (var call : assistant.getToolCalls()) {
                total += 12L + text(call.id(), model) + text(call.name(), model) + text(call.arguments(), model);
            }
        }
        if (message instanceof ToolResponseMessage tool) {
            for (var response : tool.getResponses()) {
                total += 12L + text(response.id(), model) + text(response.name(), model) + text(response.responseData(), model);
            }
        }
        if (message instanceof MediaContent content) total += (long) content.getMedia().size() * imageReserve;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static int count(Prompt prompt, String model, int imageReserve) {
        long total = 32;
        for (Message message : prompt.getInstructions()) total += message(message, model, imageReserve);
        if (prompt.getOptions() instanceof ToolCallingChatOptions tools && tools.getToolCallbacks() != null) {
            for (var callback : tools.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                total += 16L + text(definition.name(), model) + text(definition.description(), model)
                        + text(definition.inputSchema(), model);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
