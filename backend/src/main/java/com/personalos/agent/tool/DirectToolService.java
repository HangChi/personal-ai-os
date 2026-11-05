package com.personalos.agent.tool;

import com.personalos.common.ai.AgentExecutionProperties;
import com.personalos.common.ai.AgentTurnBudget;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.ToolContributor;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Explicit user commands use existing tools, without a model request or an approval bypass. */
@Service
public class DirectToolService {
    private static final Set<String> BUILTINS = Set.of("createTask", "findTasks", "getTask", "updateTaskDetails",
            "setTaskCompleted", "deleteTask", "scheduleTask", "setTaskPriority", "createCalendarEvent",
            "findCalendarEvents", "findDueReminders", "markReminderRead", "deleteCalendarEvent",
            "searchKnowledge", "searchWeb", "saveMemory", "getPlan");
    private final Map<String, ToolCallback> tools = new TreeMap<>();
    private final IdempotencyService idempotency;
    private final JsonCodec json;
    private final AgentExecutionProperties properties;

    public record Description(String name, String description, String inputSchema) {}
    public record Execution(String requestId, String tool, ToolExecutionResult result, boolean replayed) {}

    @Autowired
    public DirectToolService(ObjectProvider<ToolContributor> contributors, ObjectProvider<ToolCallbackProvider> providers,
                             IdempotencyService idempotency, JsonCodec json, AgentExecutionProperties properties) {
        this(collect(contributors, providers), idempotency, json, properties);
    }

    DirectToolService(List<ToolCallback> callbacks, IdempotencyService idempotency, JsonCodec json) {
        this(callbacks, idempotency, json, new AgentExecutionProperties(12, 2));
    }

    DirectToolService(List<ToolCallback> callbacks, IdempotencyService idempotency, JsonCodec json, AgentExecutionProperties properties) {
        this.idempotency = idempotency;
        this.json = json;
        this.properties = properties;
        for (var callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (allowed(name) && tools.putIfAbsent(name, callback) != null) {
                throw new IllegalStateException("快捷工具名称重复：" + name);
            }
        }
    }

    private static List<ToolCallback> collect(ObjectProvider<ToolContributor> contributors, ObjectProvider<ToolCallbackProvider> providers) {
        List<ToolCallback> callbacks = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> callbacks.addAll(List.of(ToolCallbacks.from(contributor.toolObjects().toArray()))));
        providers.orderedStream().forEach(provider -> callbacks.addAll(List.of(provider.getToolCallbacks())));
        return callbacks;
    }

    static boolean allowed(String name) { return BUILTINS.contains(name) || name.startsWith("mcp_"); }

    public List<Description> catalog() {
        return tools.values().stream().map(callback -> {
            var definition = callback.getToolDefinition();
            return new Description(definition.name(), definition.description(), definition.inputSchema());
        }).toList();
    }

    public Execution execute(String conversationId, String requestId, String tool, Map<String, Object> arguments, Runnable checkpoint) {
        try {
            if (requestId == null || requestId.length() != 36 || !UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw invalid("快捷操作必须携带 UUID 格式的 Idempotency-Key"); }
        if (tool == null || !tools.containsKey(tool)) throw invalid("工具不存在或不支持直接调用，请输入 /tools 查看可用工具");
        if (arguments == null) throw invalid("arguments 必须是 JSON 对象");
        if (json.toJson(arguments).getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid("工具参数不能超过 64KB");
        requestId = requestId.toLowerCase(Locale.ROOT);
        String turnId = "cmd:" + requestId;
        Map<String, Object> digestInput = new TreeMap<>();
        digestInput.put("conversationId", conversationId);
        digestInput.put("tool", tool);
        digestInput.put("arguments", canonical(arguments));
        var request = new IdempotencyRequest(turnId, OperationSource.TOOL, "directCommand", json.digestOf(digestInput), conversationId, turnId);
        var saved = idempotency.execute(request, () -> {
            Map<String, Object> context = new HashMap<>();
            context.put(ToolContextKeys.CONVERSATION_ID, conversationId);
            context.put(ToolContextKeys.TURN_ID, turnId);
            context.put(ToolContextKeys.EXECUTION_BUDGET, new AgentTurnBudget(properties));
            context.put(ToolContextKeys.CONTROL, checkpoint);
            ToolExecutionResult result;
            try {
                result = json.fromJson(tools.get(tool).call(json.toJson(arguments), new ToolContext(context)), ToolExecutionResult.class);
                if (result == null || result.status() == null) throw new IllegalStateException("工具响应无效");
            } catch (BusinessException ex) {
                result = ToolExecutionResult.rejected(ex.getMessage());
            } catch (IllegalArgumentException ex) {
                result = ToolExecutionResult.rejected("工具参数与定义不匹配，请输入 /tools 查看参数说明");
            } catch (RuntimeException ex) {
                result = ToolExecutionResult.error("工具执行结果未确认，请先核对目标状态，不要直接重试");
            }
            var outcome = switch (result.status()) {
                case "OK", "DUPLICATE" -> IdempotencyOutcome.SUCCESS;
                case "REQUIRES_CONFIRMATION" -> IdempotencyOutcome.REQUIRES_CONFIRMATION;
                case "REJECTED" -> IdempotencyOutcome.REJECTED;
                default -> IdempotencyOutcome.FAILED;
            };
            return IdempotentResult.of(json.toJson(result), outcome);
        });
        return new Execution(requestId, tool, json.fromJson(saved.responsePayload(), ToolExecutionResult.class), saved.replayed());
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(DirectToolService::canonical).toList();
        return value;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
