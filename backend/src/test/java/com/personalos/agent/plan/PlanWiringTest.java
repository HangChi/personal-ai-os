package com.personalos.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.agent.plan.controller.AgentPlanController;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.agent.plan.tool.AgentPlanToolContributor;
import com.personalos.agent.plan.tool.AgentPlanTools;
import com.personalos.agent.plan.tool.PlanToolCallRecorder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计划模块的接线测试：控制器返回什么、贡献者贡献了什么、旁路记录器拦掉了什么。
 *
 * <p>这三处单独看都很薄，但都属于「错了也不报错」的位置：
 * 控制器返回 404 会让前端把正常情况当错误；贡献者漏注册会让工具根本不可见；
 * 记录器不排除计划工具会让面板出现「第 2 步调用了 completePlanStep」的噪音。
 */
class PlanWiringTest {

    @Test
    @DisplayName("Controller: 没有计划时返回 data=null，而不是抛 404")
    void controllerReturnsNullWhenNoPlan() {
        AgentPlanService service = mock(AgentPlanService.class);
        given(service.latestPlan("conv-1")).willReturn(Optional.empty());

        AgentPlanController controller = new AgentPlanController(service);

        assertThat(controller.latest("conv-1").data()).isNull();
    }

    @Test
    @DisplayName("Contributor: 把计划工具交给对话模块（漏了它模型就看不到这些工具）")
    void contributorExposesTools() {
        AgentPlanTools tools = mock(AgentPlanTools.class);
        AgentPlanToolContributor contributor = new AgentPlanToolContributor(tools);

        assertThat(contributor.toolObjects()).containsExactly(tools);
    }

    @Test
    @DisplayName("Recorder: 计划工具自身不参与步骤绑定（否则面板全是状态变更噪音）")
    void recorderIgnoresPlanTools() {
        AgentPlanService service = mock(AgentPlanService.class);
        PlanToolCallRecorder recorder = new PlanToolCallRecorder(service);

        recorder.onToolSuccess("conv-1", "completePlanStep", "进度 1/3");
        recorder.onToolSuccess("conv-1", "getPlan", "当前无计划");

        verify(service, never()).bindToolCall(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Recorder: 业务工具的结果归集到步骤上")
    void recorderBindsBusinessTools() {
        AgentPlanService service = mock(AgentPlanService.class);
        PlanToolCallRecorder recorder = new PlanToolCallRecorder(service);

        recorder.onToolSuccess("conv-1", "createTask", "已创建任务：交周报");

        verify(service).bindToolCall("conv-1", "createTask", "已创建任务：交周报");
    }

    @Test
    @DisplayName("Recorder: 业务工具失败时把服务层提示透传给模型")
    void recorderPassesFailureHint() {
        AgentPlanService service = mock(AgentPlanService.class);
        given(service.onToolFailure("conv-1", "deleteTask", "任务不存在"))
                .willReturn(Optional.of("步骤 2 已标记为失败。"));
        PlanToolCallRecorder recorder = new PlanToolCallRecorder(service);

        assertThat(recorder.onToolFailure("conv-1", "deleteTask", "任务不存在"))
                .contains("步骤 2 已标记为失败");
    }

    @Test
    @DisplayName("Recorder: 没有会话标识时不查库（工具可能在没有会话的入口被调用）")
    void recorderIgnoresMissingConversation() {
        AgentPlanService service = mock(AgentPlanService.class);
        PlanToolCallRecorder recorder = new PlanToolCallRecorder(service);

        recorder.onToolSuccess(null, "createTask", "已创建");
        assertThat(recorder.onToolFailure(null, "createTask", "失败")).isNull();

        verify(service, never()).bindToolCall(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("PlanDto.doneCount(): 跳过也算「已处理」，否则进度永远差一格")
    void doneCountIncludesSkipped() {
        com.personalos.agent.plan.dto.PlanDto plan = new com.personalos.agent.plan.dto.PlanDto(
                "p", "conv-1", "目标", "ACTIVE",
                List.of(
                        new com.personalos.agent.plan.dto.PlanStepDto(1, "a", "DONE", null, null, null, null, null, null),
                        new com.personalos.agent.plan.dto.PlanStepDto(2, "b", "SKIPPED", null, null, null, null, null, null),
                        new com.personalos.agent.plan.dto.PlanStepDto(3, "c", "PENDING", null, null, null, null, null, null)),
                null, null);

        assertThat(plan.doneCount()).isEqualTo(2);
    }
}
