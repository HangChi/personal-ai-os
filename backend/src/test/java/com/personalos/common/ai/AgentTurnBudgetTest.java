package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.*;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.exception.BusinessException;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AgentTurnBudgetTest {
    @Test
    void sameFailedArgumentsAreNeverExecutedAgainButChangedArgumentsAreAllowed() {
        var state = new AgentTurnBudget(new AgentExecutionProperties(12, 2));
        state.beforeTool("findTasks:old");
        state.record("findTasks:old", ToolExecutionResult.error("失败"));
        assertThatCode(() -> state.beforeTool("findTasks:corrected")).doesNotThrowAnyException();
        assertThatThrownBy(() -> state.beforeTool("findTasks:old")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(state::beforeModel).isInstanceOf(BusinessException.class);
    }

    @Test
    void consecutiveFailuresStopAndSuccessResetsCounter() {
        var state = new AgentTurnBudget(new AgentExecutionProperties(12, 2));
        state.record("a", ToolExecutionResult.rejected("参数错误"));
        state.record("b", ToolExecutionResult.ok("成功", null));
        state.record("c", ToolExecutionResult.error("失败"));
        assertThatCode(state::beforeModel).doesNotThrowAnyException();
        state.record("d", ToolExecutionResult.error("仍然失败"));
        assertThatThrownBy(state::beforeModel).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> state.beforeTool("e")).isInstanceOf(BusinessException.class);
    }

    @Test
    void modelRoundBudgetStopsEvenWhenFrameworkReturnsErrorsInsteadOfStopping() {
        var raw = new ScriptedToolCallingChatModel();
        for (int i = 0; i < 30; i++) raw.enqueueToolCall("read", "{}");
        var state = new AgentTurnBudget(new AgentExecutionProperties(4, 2));
        var contextBudget = new ContextBudgetService(new AiProperties(new AiProperties.Memory(20, 131072), "sys"));
        AtomicInteger executions = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read").description("查询")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            public String call(String input) { executions.incrementAndGet(); return "[]"; }
        };
        var manager = DefaultToolCallingManager.builder().maxCallsPerTool(1).maxTotalToolCalls(2)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE).build();
        var client = ChatClient.builder(new ContextGuardChatModel(raw, contextBudget)).defaultTools(tool)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build()).build();
        assertThatThrownBy(() -> client.prompt("查询").toolContext(Map.of(ToolContextKeys.EXECUTION_BUDGET, state))
                .stream().content().blockLast(Duration.ofSeconds(2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工具调用已停止");
        assertThat(raw.receivedInstructions()).hasSize(4);
        assertThat(executions.get()).isEqualTo(1);
        // 新一轮状态不会继承上一轮的计数。
        assertThatCode(() -> new AgentTurnBudget(new AgentExecutionProperties(4, 2)).beforeModel()).doesNotThrowAnyException();
    }

    @Test
    void invalidBudgetsFailAtStartup() {
        assertThatThrownBy(() -> new AgentExecutionProperties(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentExecutionProperties(2, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
