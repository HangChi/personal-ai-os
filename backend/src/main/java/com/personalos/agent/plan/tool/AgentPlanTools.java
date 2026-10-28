package com.personalos.agent.plan.tool;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 使用的计划工具。
 *
 * <h2>为什么需要显式计划</h2>
 * 单轮工具循环里，模型「边想边做」——这在两三步的任务上没问题，但任务一长就会出两类
 * 典型问题：做到一半忘了还剩几步；某一步失败后反复重试同一条路，而不是换方案。
 * 把计划落到表里之后，步骤成为**可读的外部状态**：模型能自查（getPlan），
 * 用户能看到进度，失败也能定位到具体一步。
 *
 * <h2>工具的分工</h2>
 * <ul>
 *   <li>{@code createPlan} —— 任务开始时写计划（**先写再做**，不是做完再补）</li>
 *   <li>{@code startPlanStep} / {@code completePlanStep} / {@code failPlanStep} —— 推进状态</li>
 *   <li>{@code revisePlan} —— 换路线；旧计划保留为 ABORTED，历史不丢</li>
 *   <li>{@code getPlan} —— 上下文被裁剪后的自查手段</li>
 * </ul>
 *
 * <p>工具结果里的状态码由 {@link ToolExecutionResult} 表达，模型据此区分
 * 「执行了」「被拒绝」「需要确认」，而不是靠读自然语言猜。
 */
@Component
public class AgentPlanTools {

    private final ToolExecutor toolExecutor;
    private final AgentPlanService planService;

    public AgentPlanTools(ToolExecutor toolExecutor, AgentPlanService planService) {
        this.toolExecutor = toolExecutor;
        this.planService = planService;
    }

    @Tool(name = "createPlan",
            description = "为一个多步骤任务建立执行计划。**仅当任务需要 2 步以上且步骤之间有依赖时调用**"
                    + "（例如「整理上周会议并给每人发提醒」）。单步任务、纯问答、单次查询不要调用。"
                    + "计划建立后，系统会在每一轮把「当前该做哪一步」作为执行约束推送给你。"
                    + "同一会话只保留一个进行中的计划，新计划会取代旧的。")
    public String createPlan(
            @ToolParam(description = "目标的一句话复述，例如：把本周所有未完成任务按优先级重排") String goal,
            @ToolParam(description = "有序的步骤标题列表，2~12 步。每步应是一个可验证的动作，"
                    + "例如 [\"查询未完成任务\", \"按截止时间排序\", \"更新优先级\"]") List<String> steps,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("goal", goal);
            args.put("steps", steps);
        });
        return toolExecutor.write("createPlan", arguments, toolContext, () -> {
            PlanDto plan = planService.createPlan(ToolExecutor.conversationId(toolContext), goal, steps);
            return ToolExecutionResult.ok("已建立 " + plan.steps().size() + " 步计划，请从第 1 步开始。", plan);
        });
    }

    @Tool(name = "startPlanStep",
            description = "开始执行计划中的某一步。**必须按序推进**：只能开始第一个尚未结束的步骤，"
                    + "跳到后面的步骤会被拒绝。失败过的步骤可以通过它重试。"
                    + "开始后该步状态为 RUNNING，期间的工具调用结果会自动归到这一步。")
    public String startPlanStep(
            @ToolParam(description = "步骤序号（从 1 开始）") Integer seq,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("seq", seq));
        return toolExecutor.write("startPlanStep", arguments, toolContext, () -> {
            PlanDto plan = planService.startStep(ToolExecutor.conversationId(toolContext), requireSeq(seq));
            return ToolExecutionResult.ok("第 " + seq + " 步已开始。", plan);
        });
    }

    @Tool(name = "completePlanStep",
            description = "把计划中的某一步标记为完成。**需要产出证据**：这一步必须已经调用过工具，"
                    + "或者在 summary 里写下可核对的结果 —— 两者都没有会被拒绝。"
                    + "不要在没做任何事的情况下声称完成。")
    public String completePlanStep(
            @ToolParam(description = "步骤序号（从 1 开始）") Integer seq,
            @ToolParam(required = false, description = "这一步的结论或产出；本步没有调用工具时必填") String summary,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("seq", seq);
            args.put("summary", summary);
        });
        return toolExecutor.write("completePlanStep", arguments, toolContext, () -> {
            PlanDto plan = planService.completeStep(ToolExecutor.conversationId(toolContext), requireSeq(seq), summary);
            return ToolExecutionResult.ok(planMessage(plan), plan);
        });
    }

    @Tool(name = "failPlanStep",
            description = "把计划中的某一步标记为失败。失败后可以重试该步（startPlanStep），"
                    + "或用 revisePlan 换一条路线。不要反复用同样的参数重试。")
    public String failPlanStep(
            @ToolParam(description = "步骤序号（从 1 开始）") Integer seq,
            @ToolParam(description = "失败原因，例如：任务不存在 / 缺少必要参数 / 权限不足") String reason,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("seq", seq);
            args.put("reason", reason);
        });
        return toolExecutor.write("failPlanStep", arguments, toolContext, () -> {
            PlanDto plan = planService.failStep(ToolExecutor.conversationId(toolContext), requireSeq(seq), reason);
            return ToolExecutionResult.ok("第 " + seq + " 步已标记失败。", plan);
        });
    }

    @Tool(name = "skipPlanStep",
            description = "跳过计划中的某一步（例如它依赖的数据不存在）。跳过计入已处理，计划仍能正常完成。")
    public String skipPlanStep(
            @ToolParam(description = "步骤序号（从 1 开始）") Integer seq,
            @ToolParam(required = false, description = "跳过原因，可省略") String reason,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("seq", seq);
            args.put("reason", reason);
        });
        return toolExecutor.write("skipPlanStep", arguments, toolContext, () -> {
            PlanDto plan = planService.skipStep(ToolExecutor.conversationId(toolContext), requireSeq(seq), reason);
            return ToolExecutionResult.ok(planMessage(plan), plan);
        });
    }

    @Tool(name = "revisePlan",
            description = "放弃当前计划并建立新计划（换路线）。原计划保留为已放弃状态，便于回溯。"
                    + "当某步失败且重试也无意义、或用户改了需求时调用。")
    public String revisePlan(
            @ToolParam(description = "为什么要换路线，例如：任务接口不可用，改为先导出再处理") String reason,
            @ToolParam(description = "新的步骤标题列表，2~12 步") List<String> steps,
            @ToolParam(required = false, description = "新的目标复述；省略则沿用原目标") String goal,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("reason", reason);
            args.put("steps", steps);
            args.put("goal", goal);
        });
        return toolExecutor.write("revisePlan", arguments, toolContext, () -> {
            PlanDto plan = planService.revisePlan(ToolExecutor.conversationId(toolContext), reason, goal, steps);
            return ToolExecutionResult.ok("已按新路线重建计划，共 " + plan.steps().size() + " 步。", plan);
        });
    }

    @Tool(name = "getPlan",
            description = "查看当前会话的计划与各步状态。上下文被裁剪后用它确认「还剩哪几步没做」。")
    public String getPlan(ToolContext toolContext) {
        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
        });
        return toolExecutor.read("getPlan", arguments, toolContext, () -> planService
                .latestPlan(ToolExecutor.conversationId(toolContext))
                .map(plan -> ToolExecutionResult.ok(planMessage(plan), plan))
                // 没有计划是正常状态，不是错误：用 OK + 空数据表达，
                // 避免模型把「还没建计划」当成失败而反复重试
                .orElseGet(() -> ToolExecutionResult.ok("当前会话还没有执行计划。", null)));
    }

    private static int requireSeq(Integer seq) {
        if (seq == null) {
            throw new com.personalos.common.exception.BusinessException(
                    com.personalos.common.api.ErrorCode.VALIDATION_FAILED, "步骤序号不能为空");
        }
        return seq;
    }

    /** 把进度写成一句话，模型据此判断「是否该收尾」 */
    private static String planMessage(PlanDto plan) {
        long done = plan.doneCount();
        return "计划进度 " + done + "/" + plan.steps().size() + "（" + plan.status() + "）";
    }
}
