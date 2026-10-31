package com.personalos.common.ai.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.*;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class ToolExecutorTest {
    @Test
    void returnedErrorsNotifyPlanAndBlockSameArgumentsWithoutReexecuting() {
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(any(), any())).thenAnswer(call ->
                ((Supplier<IdempotentResult>) call.getArgument(1)).get());
        var recorder = mock(ToolCallRecorder.class);
        when(recorder.onToolFailure(any(), any(), any())).thenReturn("修正参数或换路线");
        var factory = new StaticListableBeanFactory();
        factory.addBean("recorder", recorder);
        var executor = new ToolExecutor(idempotency, new JsonCodec(new ObjectMapper()),
                factory.getBeanProvider(ToolCallRecorder.class));
        var budget = new AgentTurnBudget(new AgentExecutionProperties(12, 2));
        var context = new ToolContext(Map.of(ToolContextKeys.CONVERSATION_ID, "conv", ToolContextKeys.TURN_ID, "turn",
                ToolContextKeys.EXECUTION_BUDGET, budget));
        AtomicInteger executions = new AtomicInteger();
        Supplier<ToolExecutionResult> action = () -> { executions.incrementAndGet(); return ToolExecutionResult.error("MCP 错误"); };
        assertThat(executor.read("read", Map.of("q", "bad"), context, action)).contains("ERROR", "修正参数");
        verify(recorder).onToolFailure("conv", "read", "MCP 错误");
        assertThatThrownBy(() -> executor.read("read", Map.of("q", "bad"), context, action))
                .isInstanceOf(BusinessException.class);
        assertThat(executions.get()).isEqualTo(1);
    }
}
