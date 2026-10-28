package com.personalos.agent.plan.tool;

import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.common.ai.tool.ToolCallRecorder;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把工具执行结果归集到「正在执行的计划步骤」。
 *
 * <h2>为什么不让模型自报</h2>
 * 「我调用了 createTask 并创建了任务 X」是模型的**叙述**，不是事实。
 * 它可能漏报、错报工具名，甚至什么都没调就声称调了。
 * 计划面板要回答的是「实际发生了什么」，所以绑定由执行外壳完成。
 *
 * <h2>为什么排除计划工具自身</h2>
 * {@code startPlanStep} / {@code completePlanStep} 也是在调工具，但它们的「结果」
 * 就是状态变更本身，归集进去只会让面板显示「第 2 步调用了 completePlanStep」这种噪音。
 */
@Component
public class PlanToolCallRecorder implements ToolCallRecorder {

    /** 计划工具自身：不参与步骤绑定 */
    private static final Set<String> PLAN_TOOLS = Set.of(
            "createPlan", "startPlanStep", "completePlanStep", "failPlanStep",
            "skipPlanStep", "revisePlan", "getPlan");

    private final AgentPlanService planService;

    public PlanToolCallRecorder(AgentPlanService planService) {
        this.planService = planService;
    }

    @Override
    public void onToolSuccess(String conversationId, String toolName, String resultSummary) {
        if (conversationId == null || PLAN_TOOLS.contains(toolName)) {
            return;
        }
        planService.bindToolCall(conversationId, toolName, resultSummary);
    }

    @Override
    public String onToolFailure(String conversationId, String toolName, String message) {
        if (conversationId == null || PLAN_TOOLS.contains(toolName)) {
            return null;
        }
        return planService.onToolFailure(conversationId, toolName, message).orElse(null);
    }
}
