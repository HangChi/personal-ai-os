package com.personalos.common.ai;

import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;

/** 随 ToolContext 传递的单轮状态；不存进共享单例，不把提示词或工具参数写入日志。 */
public final class AgentTurnBudget {
    private final int maxModelCalls;
    private final int maxConsecutiveFailures;
    private final Set<String> failedCalls = new HashSet<>();
    private int modelCalls;
    private int consecutiveFailures;
    private boolean stopped;

    public AgentTurnBudget(AgentExecutionProperties properties) {
        maxModelCalls = properties.maxModelCalls();
        maxConsecutiveFailures = properties.maxConsecutiveFailures();
    }

    public static AgentTurnBudget from(Map<String, Object> context) {
        return context != null && context.get(ToolContextKeys.EXECUTION_BUDGET) instanceof AgentTurnBudget budget
                ? budget : null;
    }

    public static void beforeModel(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions tools) {
            AgentTurnBudget budget = from(tools.getToolContext());
            if (budget != null) {
                budget.beforeModel();
                org.slf4j.LoggerFactory.getLogger(AgentTurnBudget.class).info(
                        "模型请求 turnId={} conversationId={} model={} call={}",
                        tools.getToolContext().get(ToolContextKeys.TURN_ID),
                        tools.getToolContext().get(ToolContextKeys.CONVERSATION_ID), options.getModel(), budget.modelCalls());
            }
        }
    }

    public synchronized void beforeModel() {
        if (stopped || modelCalls >= maxModelCalls) stop();
        modelCalls++;
    }

    public synchronized void beforeTool(String fingerprint) {
        // 同参数失败后本轮不再重试；允许修正参数或改走其它工具。
        if (stopped || failedCalls.contains(fingerprint)) stop();
    }

    public synchronized void record(String fingerprint, ToolExecutionResult result) {
        if ("ERROR".equals(result.status()) || "REJECTED".equals(result.status())) {
            failedCalls.add(fingerprint);
            if (++consecutiveFailures >= maxConsecutiveFailures) stopped = true;
        } else {
            consecutiveFailures = 0;
        }
    }

    public synchronized int modelCalls() { return modelCalls; }

    private void stop() {
        stopped = true;
        throw new BusinessException(ErrorCode.AGENT_EXECUTION_LIMIT);
    }
}
