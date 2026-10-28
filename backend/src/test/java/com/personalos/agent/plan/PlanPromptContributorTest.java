package com.personalos.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.dto.PlanStepDto;
import com.personalos.agent.plan.service.AgentPlanService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanPromptContributor} 单元测试。
 *
 * <p>这个类决定「引擎能不能真正约束模型」，测的是四件事：
 * <ol>
 *   <li>没有计划时**必须一个字都不注入**（否则每轮白花上下文）；</li>
 *   <li>当前步骤指向**正在跑的**那一步，而不是序号最小的；</li>
 *   <li>注入文本里带着「调用 completePlanStep(N)」这种**可执行指令**，而不只是状态播报；</li>
 *   <li>超长时截断 —— 计划把记忆和知识库挤出去，等于用约束换掉了事实。</li>
 * </ol>
 */
class PlanPromptContributorTest {

    private AgentPlanService planService;
    private PlanPromptContributor contributor;

    @BeforeEach
    void setUp() {
        planService = mock(AgentPlanService.class);
        contributor = new PlanPromptContributor(planService, new PlanProperties(true, 1200));
    }

    private static PlanStepDto step(int seq, String title, String status) {
        return new PlanStepDto(seq, title, status, null, null, null, null, null, null);
    }

    private static PlanDto plan(String status, PlanStepDto... steps) {
        return new PlanDto("plan-1", "conv-1", "把本周任务按优先级重排", status,
                List.of(steps), Instant.parse("2026-10-28T00:00:00Z"), Instant.now());
    }

    @Test
    @DisplayName("没有计划时不注入任何内容（绝大多数对话属于这一类）")
    void shouldBeEmptyWithoutPlan() {
        given(planService.activePlan("conv-1")).willReturn(Optional.empty());

        assertThat(contributor.contribute("你好", "conv-1")).isEmpty();
    }

    @Test
    @DisplayName("没有会话标识时不注入 —— 计划是会话级的，不能泄漏给所有调用方")
    void shouldBeEmptyWithoutConversation() {
        assertThat(contributor.contribute("你好", null)).isEmpty();
        assertThat(contributor.contribute("你好", "  ")).isEmpty();
    }

    @Test
    @DisplayName("无参重载恒返回空：它拿不到会话，猜不出该注入谁的计划")
    void shouldBeEmptyForParameterlessCall() {
        assertThat(contributor.contribute()).isEmpty();
    }

    @Test
    @DisplayName("关闭开关后不注入（计划退回记录层）")
    void shouldRespectSwitch() {
        PlanPromptContributor disabled = new PlanPromptContributor(planService, new PlanProperties(false, 1200));

        assertThat(disabled.contribute("你好", "conv-1")).isEmpty();
    }

    @Test
    @DisplayName("注入块包含目标、进度、当前步骤与可执行指令")
    void shouldRenderActionableBlock() {
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan("ACTIVE",
                step(1, "查询未完成任务", "DONE"),
                step(2, "按截止时间排序", "RUNNING"),
                step(3, "更新优先级", "PENDING"))));

        String block = contributor.contribute("继续", "conv-1");

        assertThat(block).startsWith("## 执行计划（引擎强制，按序执行）");
        assertThat(block).contains("目标：把本周任务按优先级重排");
        assertThat(block).contains("进度：1/3");
        assertThat(block).contains("当前步骤：第 2 步「按截止时间排序」");
        // 指令必须是可执行的，而不只是「你现在在第 2 步」
        assertThat(block).contains("completePlanStep(2)");
        assertThat(block).contains("failPlanStep(2)");
        assertThat(block).contains("后续步骤：3 更新优先级");
    }

    @Test
    @DisplayName("当前步骤取「正在跑的」而不是序号最小的未完成步骤")
    void shouldPreferRunningStep() {
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan("ACTIVE",
                // 第 1 步被漏掉（PENDING），第 2 步却在跑：约束应指向真正在做的那步
                step(1, "查询", "PENDING"),
                step(2, "排序", "RUNNING"))));

        String block = contributor.contribute("继续", "conv-1");

        assertThat(block).contains("当前步骤：第 2 步「排序」");
    }

    @Test
    @DisplayName("没有 RUNNING 时取第一个未终态步骤（含失败的那一步）")
    void shouldFallBackToFirstOpenStep() {
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan("ACTIVE",
                step(1, "查询", "DONE"),
                step(2, "排序", "FAILED"),
                step(3, "更新", "PENDING"))));

        String block = contributor.contribute("继续", "conv-1");

        assertThat(block).contains("当前步骤：第 2 步「排序」");
    }

    @Test
    @DisplayName("全部步骤结束时给收尾提示，而不是抛异常")
    void shouldGuideWrappingUp() {
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan("ACTIVE",
                step(1, "查询", "DONE"),
                step(2, "排序", "SKIPPED"))));

        String block = contributor.contribute("继续", "conv-1");

        assertThat(block).contains("所有步骤已结束");
    }

    @Test
    @DisplayName("剩余步骤过多时只列前几个（计划不该把上下文吃光）")
    void shouldCollapseLongRemainingList() {
        List<PlanStepDto> steps = new ArrayList<>();
        steps.add(step(1, "第一步", "RUNNING"));
        for (int i = 2; i <= 10; i++) {
            steps.add(step(i, "第" + i + "步", "PENDING"));
        }
        PlanDto plan = new PlanDto("plan-1", "conv-1", "目标", "ACTIVE", steps, null, null);
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan));

        String block = contributor.contribute("继续", "conv-1");

        assertThat(block).contains("等共 9 步");
        assertThat(block).doesNotContain("10 第10步");
    }

    @Test
    @DisplayName("超长注入被截断到配置上限")
    void shouldTruncateToConfiguredLimit() {
        PlanPromptContributor tiny = new PlanPromptContributor(planService, new PlanProperties(true, 120));
        given(planService.activePlan("conv-1")).willReturn(Optional.of(plan("ACTIVE",
                step(1, "查询未完成任务列表并逐条核对截止时间", "RUNNING"),
                step(2, "排序", "PENDING"))));

        String block = tiny.contribute("继续", "conv-1");

        assertThat(block.length()).isLessThanOrEqualTo(120);
        assertThat(block).contains("已截断");
    }
}
