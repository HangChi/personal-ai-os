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

 @Test void shellFailuresRecordBudgetAndPreserveUncertainty(){
  for(boolean withBudget:java.util.List.of(false,true))for(boolean conflict:java.util.List.of(false,true)){
   var idem=mock(IdempotencyService.class);when(idem.execute(any(),any())).thenThrow(conflict?new BusinessException(com.personalos.common.api.ErrorCode.BUSINESS_CONFLICT,"参数冲突"):new IllegalStateException("private credential"));
   var executor=new ToolExecutor(idem,new JsonCodec(new ObjectMapper()),null);
   var budget=new AgentTurnBudget(new AgentExecutionProperties(12,2));
   var context=withBudget?new ToolContext(Map.of(ToolContextKeys.EXECUTION_BUDGET,budget)):null;var calls=new AtomicInteger();
   String result=executor.write("startPlanStep",Map.of(),context,()->{calls.incrementAndGet();return ToolExecutionResult.ok("ok",null);});
   assertThat(result).contains(conflict?"REJECTED":"ERROR").doesNotContain("private credential");assertThat(calls).hasValue(0);
  }
 }
 @Test void absentAndBlankRecordersDoNotChangeToolOutcome(){
  var idem=mock(IdempotencyService.class);when(idem.execute(any(),any())).thenAnswer(call->((Supplier<IdempotentResult>)call.getArgument(1)).get());
  for(String hint:java.util.Arrays.asList(null," ")){
   var recorder=mock(ToolCallRecorder.class);when(recorder.onToolFailure(any(),any(),any())).thenReturn(hint);var factory=new StaticListableBeanFactory();factory.addBean("recorder",recorder);
   var executor=new ToolExecutor(idem,new JsonCodec(new ObjectMapper()),factory.getBeanProvider(ToolCallRecorder.class));
   assertThat(executor.read("read",Map.of(),null,()->ToolExecutionResult.error("失败"))).contains("失败");
  }
  var executor=new ToolExecutor(idem,new JsonCodec(new ObjectMapper()),null);
  assertThat(executor.read("read",Map.of(),null,()->ToolExecutionResult.error("失败"))).contains("失败");
  assertThat(executor.write("write",Map.of(),null,()->ToolExecutionResult.ok("成功",null))).contains("成功");
  for(String raw:java.util.Arrays.asList(null," "))assertThatThrownBy(()->ToolExecutor.parseUuid(raw,"id")).isInstanceOf(BusinessException.class);
  var context=mock(ToolContext.class);when(context.getContext()).thenReturn(null);assertThat(ToolExecutor.contextValue(context,"key")).isNull();assertThat(executor.read("read",Map.of(),context,()->ToolExecutionResult.ok("好",null))).contains("好");
 }
}
