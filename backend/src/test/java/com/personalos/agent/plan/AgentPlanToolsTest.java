package com.personalos.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.dto.PlanStepDto;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.agent.plan.tool.AgentPlanTools;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * {@link AgentPlanTools} 单元测试。
 *
 * <p>测的是「工具层有没有把参数正确交给服务层」以及**边界输入**（缺 seq、空计划）。
 * 工具的返回值只是给模型看的说明文本，真正需要保证的是状态真的变化了 ——
 * 因此这里用真实的 {@link ToolExecutor} 外壳不合适（它会连幂等表），
 * 改为断言「服务层被以正确参数调用」。
 *
 * <p>外壳的 write/read 被替换成「执行 supplier 并返回占位字符串」：
 * 这样 supplier 里的业务调用会真的发生，避免测出一个「什么都没做但也没报错」的假绿。
 */
class AgentPlanToolsTest {

    private ToolExecutor toolExecutor;
    private AgentPlanService planService;
    private AgentPlanTools tools;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        toolExecutor = mock(ToolExecutor.class);
        planService = mock(AgentPlanService.class);
        tools = new AgentPlanTools(toolExecutor, planService);
        toolContext = new ToolContext(Map.of(ToolContextKeys.CONVERSATION_ID, "conv-1", ToolContextKeys.TURN_ID, "turn-1"));

        given(toolExecutor.write(anyString(), anyMap(), any(), any())).willAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(3);
            action.get();
            return "ok";
        });
        given(toolExecutor.read(anyString(), anyMap(), any(), any())).willAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(3);
            action.get();
            return "ok";
        });
    }

    private static PlanDto plan(int stepCount, String status) {
        List<PlanStepDto> steps = new java.util.ArrayList<>();
        for (int i = 1; i <= stepCount; i++) {
            steps.add(new PlanStepDto(i, "第 " + i + " 步", "PENDING", null, null, null, null,
                    Instant.now(), null));
        }
        return new PlanDto("plan-1", "conv-1", "目标", status, steps, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("createPlan(): 把目标与步骤原样交给服务层")
    void createPlanShouldDelegate() {
        given(planService.createPlan(eq("conv-1"), eq("整理任务"), any())).willReturn(plan(2, "ACTIVE"));

        String result = tools.createPlan("整理任务", List.of("查询", "更新"), toolContext);

        assertThat(result).isEqualTo("ok");
        verify(planService).createPlan("conv-1", "整理任务", List.of("查询", "更新"));
    }

    @Test
    @DisplayName("startPlanStep(): 缺少步骤序号时给出可读原因，而不是空指针")
    void startStepShouldRejectMissingSeq() {
        assertThatThrownBy(() -> tools.startPlanStep(null, toolContext))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("步骤序号不能为空");
    }

    @Test
    @DisplayName("completePlanStep(): 把结论一并交给服务层（它是「这一步做完了」的唯一证据）")
    void completeStepShouldPassSummary() {
        given(planService.completeStep("conv-1", 2, "已更新 3 条")).willReturn(plan(2, "ACTIVE"));

        tools.completePlanStep(2, "已更新 3 条", toolContext);

        verify(planService).completeStep("conv-1", 2, "已更新 3 条");
    }

    @Test
    @DisplayName("failPlanStep(): 把失败原因交给服务层，供面板与重规划判断")
    void failStepShouldPassReason() {
        given(planService.failStep("conv-1", 1, "任务不存在")).willReturn(plan(2, "ACTIVE"));

        tools.failPlanStep(1, "任务不存在", toolContext);

        verify(planService).failStep("conv-1", 1, "任务不存在");
    }

    @Test
    @DisplayName("skipPlanStep(): 跳过原因可省略")
    void skipStepShouldAllowMissingReason() {
        given(planService.skipStep("conv-1", 3, null)).willReturn(plan(3, "COMPLETED"));

        tools.skipPlanStep(3, null, toolContext);

        verify(planService).skipStep("conv-1", 3, null);
    }

    @Test
    @DisplayName("revisePlan(): 目标与步骤一起交给服务层（旧计划由服务层置为 ABORTED）")
    void reviseShouldDelegate() {
        given(planService.revisePlan("conv-1", "接口不可用", "新目标", List.of("导出", "排序")))
                .willReturn(plan(2, "ACTIVE"));

        tools.revisePlan("接口不可用", List.of("导出", "排序"), "新目标", toolContext);

        verify(planService).revisePlan("conv-1", "接口不可用", "新目标", List.of("导出", "排序"));
    }

    @Test
    @DisplayName("getPlan(): 没有计划时返回 OK 而不是错误 —— 否则模型会把「还没建计划」当失败反复重试")
    void getPlanShouldReturnOkWhenNoPlan() {
        given(planService.latestPlan("conv-1")).willReturn(Optional.empty());

        assertThat(tools.getPlan(toolContext)).isEqualTo("ok");
    }

    @Test
    @DisplayName("getPlan(): 有计划时读取最近一个（不论状态）")
    void getPlanShouldReturnLatest() {
        given(planService.latestPlan("conv-1")).willReturn(Optional.of(plan(2, "COMPLETED")));

        tools.getPlan(toolContext);

        verify(planService).latestPlan("conv-1");
    }

    @Test
    @DisplayName("工具外壳的返回值透传：模型看到的是外壳给的 JSON，不是服务层的对象")
    void shouldReturnShellResult() {
        given(planService.createPlan(anyString(), anyString(), any())).willReturn(plan(1, "ACTIVE"));
        // 用 doReturn 而不是 given(...)：后者会先真实调用一次 mock 方法，
        // 触发 setUp 里注册的 answer（此时 Supplier 为 null）→ NPE
        doReturn("{\"status\":\"OK\"}").when(toolExecutor).write(anyString(), anyMap(), any(), any());

        assertThat(tools.createPlan("目标", List.of("一步"), toolContext)).isEqualTo("{\"status\":\"OK\"}");
    }

    @Test
    @DisplayName("requireSeq(): 序号合法时正常执行")
    void startShouldWorkWithSeq() {
        given(planService.startStep("conv-1", 1)).willReturn(plan(2, "ACTIVE"));

        tools.startPlanStep(1, toolContext);

        verify(planService).startStep("conv-1", 1);
    }

    @Test
    @DisplayName("completePlanStep(): 结论可省略（只标完成）")
    void completeShouldAllowMissingSummary() {
        given(planService.completeStep("conv-1", 1, null)).willReturn(plan(1, "COMPLETED"));

        tools.completePlanStep(1, null, toolContext);

        verify(planService).completeStep("conv-1", 1, null);
    }

    @Test
    @DisplayName("createPlan(): 服务层拒绝时异常向上抛（由外壳转成 REJECTED 给模型）")
    void createShouldPropagateBusinessError() {
        given(planService.createPlan(anyString(), anyString(), any()))
                .willThrow(new BusinessException(com.personalos.common.api.ErrorCode.VALIDATION_FAILED, "步骤过多"));

        assertThatThrownBy(() -> tools.createPlan("目标", List.of("一步"), toolContext))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("步骤过多");
    }

    @Test
    @DisplayName("ToolExecutionResult 的构造仍可用（工具层依赖它的 OK 语义）")
    void resultEnvelopeStillUsable() {
        assertThat(ToolExecutionResult.ok("已建立计划", null).status()).isEqualTo("OK");
    }
}
