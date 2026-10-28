package com.personalos.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.entity.AgentPlanEntity;
import com.personalos.agent.plan.entity.AgentPlanStepEntity;
import com.personalos.agent.plan.entity.PlanStatus;
import com.personalos.agent.plan.entity.StepStatus;
import com.personalos.agent.plan.repository.AgentPlanRepository;
import com.personalos.agent.plan.repository.AgentPlanStepRepository;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.common.exception.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AgentPlanService} 单元测试。
 *
 * <p>覆盖三件「读代码看不出错」的事：
 * <ol>
 *   <li><b>唯一索引让位顺序</b>——新建计划前必须先把旧 ACTIVE 置 ABORTED 并 flush，
 *       否则 INSERT 会撞部分唯一索引（与模型配置「至多一行 active」是同一个坑）；</li>
 *   <li><b>状态机</b>——终态不可再转移，FAILED 可重试；</li>
 *   <li><b>旁路归集不抛异常</b>——没有进行中的步骤时，工具照常执行完。</li>
 * </ol>
 */
class AgentPlanServiceTest {

    private AgentPlanRepository planRepository;
    private AgentPlanStepRepository stepRepository;
    private AgentPlanService service;

    private UUID planId;

    @BeforeEach
    void setUp() {
        planRepository = mock(AgentPlanRepository.class);
        stepRepository = mock(AgentPlanStepRepository.class);
        service = new AgentPlanService(planRepository, stepRepository);
        planId = UUID.randomUUID();

        // 保存计划时补上主键（Service 用它建步骤）
        given(planRepository.saveAndFlush(any(AgentPlanEntity.class))).willAnswer(invocation -> {
            AgentPlanEntity plan = invocation.getArgument(0);
            if (plan.getId() == null) {
                ReflectionTestUtils.setField(plan, "id", planId);
            }
            return plan;
        });
        given(stepRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(stepRepository.save(any(AgentPlanStepEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(planRepository.save(any(AgentPlanEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private AgentPlanEntity activePlan(String goal) {
        AgentPlanEntity plan = new AgentPlanEntity("conv-1", goal, Instant.parse("2026-10-01T00:00:00Z"));
        ReflectionTestUtils.setField(plan, "id", planId);
        return plan;
    }

    private static AgentPlanStepEntity step(UUID planId, int seq, StepStatus status) {
        AgentPlanStepEntity step = new AgentPlanStepEntity(planId, seq, "第 " + seq + " 步", null);
        ReflectionTestUtils.setField(step, "id", UUID.randomUUID());
        switch (status) {
            case RUNNING -> step.start(Instant.now());
            case DONE -> step.complete(Instant.now());
            case FAILED -> step.fail("失败", Instant.now());
            case SKIPPED -> step.skip("跳过", Instant.now());
            default -> {
            }
        }
        return step;
    }

    // ------------------------------------------------------------ 创建

    @Test
    @DisplayName("createPlan(): 步骤为空或超过上限都被拒绝，且不落库")
    void createShouldValidateSteps() {
        assertThatThrownBy(() -> service.createPlan("conv-1", "目标", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少需要一个步骤");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < AgentPlanService.MAX_STEPS + 1; i++) {
            tooMany.add("步骤" + i);
        }
        assertThatThrownBy(() -> service.createPlan("conv-1", "目标", tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");

        verify(planRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createPlan(): 先把旧计划置 ABORTED 并 flush，再插入新计划（否则撞唯一索引）")
    void createShouldAbortPreviousPlanBeforeInsert() {
        AgentPlanEntity previous = activePlan("旧目标");
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(previous));

        service.createPlan("conv-1", "新目标", List.of("查数据", "改数据"));

        assertThat(previous.getStatus()).isEqualTo(PlanStatus.ABORTED);
        verify(planRepository).saveAndFlush(previous);
    }

    @Test
    @DisplayName("createPlan(): 步骤序号从 1 开始且与标题一一对应")
    void createShouldNumberStepsFromOne() {
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.empty());

        PlanDto plan = service.createPlan("conv-1", "整理任务", List.of("查询", "排序", "更新"));

        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.steps()).extracting("seq").containsExactly(1, 2, 3);
        assertThat(plan.steps()).extracting("title").containsExactly("查询", "排序", "更新");
        assertThat(plan.steps()).allMatch(step -> "PENDING".equals(step.status()));
    }

    // ------------------------------------------------------------ 状态机

    @Test
    @DisplayName("startStep(): 已完成的步骤不能重新开始（终态不可转移）")
    void startShouldRejectFinishedStep() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity done = step(planId, 1, StepStatus.DONE);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findByPlanIdAndSeq(planId, 1)).willReturn(Optional.of(done));

        assertThatThrownBy(() -> service.startStep("conv-1", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("startStep(): FAILED 的步骤可以重试 —— 重试比重新规划便宜得多")
    void startShouldAllowRetryOfFailedStep() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity failed = step(planId, 1, StepStatus.FAILED);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findByPlanIdAndSeq(planId, 1)).willReturn(Optional.of(failed));
        given(stepRepository.findByPlanIdOrderBySeqAsc(planId)).willReturn(List.of(failed));

        service.startStep("conv-1", 1);

        assertThat(failed.getStatus()).isEqualTo(StepStatus.RUNNING);
        assertThat(failed.getError()).as("重试时清掉上一次的失败原因").isNull();
    }

    @Test
    @DisplayName("completeStep(): 全部步骤到终态 ⇒ 计划自动 COMPLETED")
    void completeShouldFinishPlanWhenAllStepsTerminal() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity first = step(planId, 1, StepStatus.RUNNING);
        AgentPlanStepEntity second = step(planId, 2, StepStatus.DONE);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findByPlanIdAndSeq(planId, 1)).willReturn(Optional.of(first));
        given(stepRepository.findByPlanIdOrderBySeqAsc(planId)).willReturn(List.of(first, second));

        service.completeStep("conv-1", 1, "已完成");

        assertThat(first.getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(plan.getStatus())
                .as("不自动完成的话，计划会永远显示「进行中」")
                .isEqualTo(PlanStatus.COMPLETED);
    }

    @Test
    @DisplayName("completeStep(): 还有 PENDING 步骤时计划保持 ACTIVE")
    void completeShouldKeepPlanActiveWhenStepsRemain() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity first = step(planId, 1, StepStatus.RUNNING);
        AgentPlanStepEntity second = step(planId, 2, StepStatus.PENDING);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findByPlanIdAndSeq(planId, 1)).willReturn(Optional.of(first));
        given(stepRepository.findByPlanIdOrderBySeqAsc(planId)).willReturn(List.of(first, second));

        service.completeStep("conv-1", 1, null);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    }

    @Test
    @DisplayName("startStep(): 没有进行中的计划时给出可执行的提示，而不是空指针")
    void startShouldFailWhenNoActivePlan() {
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.startStep("conv-1", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("createPlan");
    }

    // ------------------------------------------------------------ 工具归集

    @Test
    @DisplayName("bindToolCall(): 没有 RUNNING 步骤时静默返回 —— 绝大多数对话根本不涉及计划")
    void bindShouldBeSilentWithoutRunningStep() {
        AgentPlanEntity plan = activePlan("目标");
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findFirstByPlanIdAndStatusOrderBySeqAsc(planId, StepStatus.RUNNING))
                .willReturn(Optional.empty());

        service.bindToolCall("conv-1", "createTask", "已创建任务");

        verify(stepRepository, never()).save(any());
    }

    @Test
    @DisplayName("bindToolCall(): 把工具名与结果摘要写到正在执行的步骤上")
    void bindShouldAttachToolToRunningStep() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity running = step(planId, 2, StepStatus.RUNNING);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findFirstByPlanIdAndStatusOrderBySeqAsc(planId, StepStatus.RUNNING))
                .willReturn(Optional.of(running));

        service.bindToolCall("conv-1", "findTasks", "找到 3 条任务");

        assertThat(running.getToolName()).isEqualTo("findTasks");
        assertThat(running.getToolResult()).contains("找到 3 条任务");
    }

    @Test
    @DisplayName("onToolFailure(): 把当前步骤标为 FAILED，并提示模型「可重试或重规划」")
    void onToolFailureShouldMarkRunningStepFailed() {
        AgentPlanEntity plan = activePlan("目标");
        AgentPlanStepEntity running = step(planId, 3, StepStatus.RUNNING);
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(plan));
        given(stepRepository.findFirstByPlanIdAndStatusOrderBySeqAsc(planId, StepStatus.RUNNING))
                .willReturn(Optional.of(running));

        Optional<String> hint = service.onToolFailure("conv-1", "deleteTask", "任务不存在");

        assertThat(running.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(running.getError()).contains("deleteTask");
        assertThat(hint).isPresent();
        assertThat(hint.get()).contains("startPlanStep").contains("revisePlan");
    }

    @Test
    @DisplayName("revisePlan(): 新目标为空时沿用旧计划的目标，不让计划变成「无题」")
    void reviseShouldReusePreviousGoal() {
        AgentPlanEntity previous = activePlan("把本周任务重排");
        given(planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc("conv-1", PlanStatus.ACTIVE))
                .willReturn(Optional.of(previous));

        PlanDto revised = service.revisePlan("conv-1", "接口不可用", null, List.of("导出", "人工排序"));

        assertThat(revised.goal()).isEqualTo("把本周任务重排");
        assertThat(previous.getStatus()).isEqualTo(PlanStatus.ABORTED);
    }

    @Test
    @DisplayName("latestPlan(): 没有计划时返回空 —— 面板据此不渲染，而不是报错")
    void latestShouldBeEmptyWhenNeverPlanned() {
        given(planRepository.findFirstByConversationIdOrderByCreatedAtDesc(anyString())).willReturn(Optional.empty());

        assertThat(service.latestPlan("conv-1")).isEmpty();
        verify(stepRepository, never()).findByPlanIdOrderBySeqAsc(any());
    }

    @Test
    @DisplayName("latestPlan(): 计划结束后仍返回最近一个 —— 否则面板一完成就空白")
    void latestShouldReturnFinishedPlan() {
        AgentPlanEntity plan = activePlan("目标");
        plan.complete(Instant.now());
        given(planRepository.findFirstByConversationIdOrderByCreatedAtDesc(eq("conv-1")))
                .willReturn(Optional.of(plan));
        given(stepRepository.findByPlanIdOrderBySeqAsc(planId))
                .willReturn(List.of(step(planId, 1, StepStatus.DONE)));

        Optional<PlanDto> latest = service.latestPlan("conv-1");

        assertThat(latest).isPresent();
        assertThat(latest.get().status()).isEqualTo("COMPLETED");
        assertThat(latest.get().doneCount()).isEqualTo(1);
    }
}
