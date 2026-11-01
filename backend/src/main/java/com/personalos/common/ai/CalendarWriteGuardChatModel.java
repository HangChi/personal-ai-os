package com.personalos.common.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Calendar operation replies are held until checked, before memory and tool advisors see them.
 * A single corrective request can produce a normal tool call; no business write is synthesized here.
 * This catches explicit completion phrases, and complements (rather than replaces) prompt rules.
 */
public final class CalendarWriteGuardChatModel implements ChatModel {
    private static final Pattern CALENDAR_CONTEXT = Pattern.compile(
            "日程|日历|会议|组会|开会|大课|教室|明天|后天|上午|下午|晚上|[0-2]?\\d[：:]\\d{2}|calendar|schedule|meeting", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATED = Pattern.compile(
            "(?m)^\\s*(?:[-*>#]\\s*)?(?:(?:好的|好|日程|会议)[，,:：\\s]*)?(?:[\\s*✅]*)(?:(?:已(?:经)?(?:成功)?|成功)[\\s*✅]*(?:(?:为你|为您|帮你|帮您|给你)[\\s*]*)?(?:创建|添加|新增|安排|登记|录入)|(?:created|added|scheduled)\\s+(?:the\\s+|your\\s+)?(?:event|meeting))", Pattern.CASE_INSENSITIVE);
    private static final String REPAIR = "[系统执行校验]\n本轮没有成功的 createCalendarEvent 工具记录，不能声称已创建日程。"
            + "先核对用户原始意图：仅查询、核实或总结已有日程时，基于查询结果说明记录是否存在，禁止新增。"
            + "用户确实要求新增或继续安排时，结合原始请求和会话前文确定日期、时间及地点，信息齐全则调用 createCalendarEvent；"
            + "若信息不足先询问，不能直接输出已创建。不得重复执行本轮已经成功的其他写操作。";
    static final String NOT_CREATED = "这项日程尚未创建：本轮没有获得创建日程工具的成功结果。请重试，或在日历中手动添加。";
    private final ChatModel delegate;

    public CalendarWriteGuardChatModel(ChatModel delegate) { this.delegate = delegate; }

    @Override
    public ChatOptions getOptions() { return delegate.getOptions(); }

    @Override
    public ChatResponse call(Prompt prompt) {
        AgentTurnBudget budget = budget(prompt);
        ChatResponse result = delegate.call(prompt);
        if (!needsRepair(prompt, budget, List.of(result))) return result;
        if (budget.claimCalendarRepair()) result = delegate.call(repair(prompt));
        return needsRepair(prompt, budget, List.of(result)) ? incomplete(result) : result;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            AgentTurnBudget budget = budget(prompt);
            if (budget == null || !calendarContext(prompt)) return delegate.stream(prompt);
            // Keep original chunks/metadata/tool-call deltas intact; only discard an unverified claim.
            return delegate.stream(prompt).collectList().flatMapMany(chunks -> {
                if (!needsRepair(prompt, budget, chunks)) return Flux.fromIterable(chunks);
                if (!budget.claimCalendarRepair()) return Flux.just(incomplete(chunks.get(chunks.size() - 1)));
                return delegate.stream(repair(prompt)).collectList().flatMapMany(repaired -> {
                    if (needsRepair(prompt, budget, repaired)) {
                        return Flux.just(incomplete(repaired.get(repaired.size() - 1)));
                    }
                    return Flux.fromIterable(repaired);
                });
            });
        });
    }

    private static AgentTurnBudget budget(Prompt prompt) {
        return prompt.getOptions() instanceof ToolCallingChatOptions options
                ? AgentTurnBudget.from(options.getToolContext()) : null;
    }

    private static boolean calendarContext(Prompt prompt) {
        // Only current user input scopes the guard; a calendar discussion months ago is irrelevant.
        for (int i = prompt.getInstructions().size() - 1; i >= 0; i--) {
            var message = prompt.getInstructions().get(i);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                if (text == null) return false;
                if (text.matches("(?s).*(?:待办|任务).*") && !text.matches("(?s).*(?:日程|日历|会议|组会|开会|大课|教室).*")) return false;
                if (text.matches("(?s).*(?:查看|查询|列出|显示|看看|有哪些|有没有|是否|了吗|了没).*")) return false;
                return CALENDAR_CONTEXT.matcher(text).find();
            }
        }
        return false;
    }

    private static boolean needsRepair(Prompt prompt, AgentTurnBudget budget, List<ChatResponse> chunks) {
        if (budget == null || !calendarContext(prompt) || budget.hasSuccessfulWrite("createCalendarEvent")
                || chunks.stream().anyMatch(ChatResponse::hasToolCalls)) return false;
        StringBuilder text = new StringBuilder();
        for (var response : chunks) {
            if (response.getResult() != null && response.getResult().getOutput().getText() != null) {
                text.append(response.getResult().getOutput().getText());
            }
        }
        return CREATED.matcher(text).find();
    }

    private static Prompt repair(Prompt prompt) {
        var instructions = new ArrayList<>(prompt.getInstructions());
        instructions.add(new SystemMessage(REPAIR));
        return new Prompt(instructions, prompt.getOptions());
    }

    private static ChatResponse incomplete(ChatResponse original) {
        return ChatResponse.builder().from(original)
                .generations(List.of(new Generation(new AssistantMessage(NOT_CREATED)))).build();
    }
}
