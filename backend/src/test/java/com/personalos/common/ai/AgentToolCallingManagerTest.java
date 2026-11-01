package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AgentToolCallingManagerTest {
    @Test
    void limitStopsStreamingWithBusinessErrorInsteadOfAnEnglishAssistantReply() {
        var raw = new ScriptedToolCallingChatModel();
        raw.enqueueToolCall("findCalendarEvents", "{}");
        raw.enqueueToolCall("findCalendarEvents", "{}");
        AtomicInteger executions = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("findCalendarEvents").description("查询日程")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            public String call(String input) { executions.incrementAndGet(); return "[]"; }
        };
        var delegate = DefaultToolCallingManager.builder().maxCallsPerTool(1).maxTotalToolCalls(3)
                .onLimitExceeded(ToolCallLimitBehavior.THROW).build();
        var manager = new AgentToolCallingManager(delegate);
        var client = ChatClient.builder(raw).defaultTools(callback)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build()).build();
        assertThatThrownBy(() -> client.prompt("查日程").stream().content().blockLast(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_EXECUTION_LIMIT))
                .hasMessageContaining("已完成的操作不会撤销")
                .hasMessageNotContaining("Tool call limit");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(raw.receivedInstructions()).hasSize(2);
    }
}
