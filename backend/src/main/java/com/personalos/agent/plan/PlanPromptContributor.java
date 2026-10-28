package com.personalos.agent.plan;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.dto.PlanStepDto;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.common.ai.PromptContributor;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 把「进行中的计划」注入每一轮系统提示（阶段 23）。
 *
 * <h2>这一步把计划从记录层抬到控制层</h2>
 * 阶段 22 的计划是**被动**的：模型想得起来就调 {@code getPlan}，想不起来就一路往前跑。
 * 而上下文窗口只带最近若干条消息 —— 一个五步任务聊到第四步时，第一步的计划早被裁掉了。
 * 本类让引擎每轮主动把「当前在第几步、还剩哪几步」摆到模型面前，
 * 计划因此不再依赖模型的记性。
 *
 * <h2>为什么需要 conversationId</h2>
 * 计划是**会话级**的。换个会话就必须不再出现 —— 这也是给 {@link PromptContributor}
 * 增加三参版本的原因：会话级上下文不能用 ThreadLocal 猜（线程池复用会串味）。
 *
 * <h2>没有计划时返回空串</h2>
 * 一条计划都没有、或计划已结束（COMPLETED / ABORTED），都返回空串。
 * 绝大多数对话属于这一类 —— 它们不该因为「有这个功能」而多出 200 字系统提示。
 */
@Component
@Order(10)
public class PlanPromptContributor implements PromptContributor {

    /** 块标题。措辞本身是契约：模型据此把这段当「执行约束」而不是「参考信息」 */
    private static final String HEADER = "## 执行计划（引擎强制，按序执行）";

    /** 剩余步骤最多列出几个；超出用「等 N 步」概括 */
    private static final int MAX_LISTED_REMAINING = 5;

    private final AgentPlanService planService;
    private final PlanProperties properties;

    public PlanPromptContributor(AgentPlanService planService, PlanProperties properties) {
        this.planService = planService;
        this.properties = properties;
    }

    /**
     * 无会话上下文时不注入。
     *
     * <p>这个重载是 SPI 的默认入口，{@code ChatPromptAssembler} 走的是三参版本；
     * 保留它是为了「有人只调无参版本」时不至于把某个会话的计划泄漏给所有调用方。
     */
    @Override
    public String contribute() {
        return "";
    }

    @Override
    public String contribute(String userInput, String conversationId) {
        if (!properties.enabled() || conversationId == null || conversationId.isBlank()) {
            return "";
        }
        return planService.activePlan(conversationId)
                .map(this::render)
                .orElse("");
    }

    /**
     * 渲染注入块。
     *
     * <p>只渲染「当前步骤」的完整要求与「剩余步骤」的标题：把每一步的 detail 全塞进来，
     * 12 步就能吃掉两千字，而模型此刻只需要知道下一步做什么。
     *
     * @param plan 进行中的计划（保证至少有一个非终态步骤）
     * @return 可直接拼进系统提示的文本
     */
    String render(PlanDto plan) {
        List<PlanStepDto> steps = plan.steps();
        PlanStepDto current = currentStep(steps);
        long done = plan.doneCount();

        StringBuilder block = new StringBuilder();
        block.append(HEADER).append("\n\n");
        block.append("目标：").append(plan.goal()).append('\n');
        block.append("进度：").append(done).append('/').append(steps.size()).append('\n');

        if (current == null) {
            // 理论上不会出现（全部终态时计划已 COMPLETED），留一条兜底而不是抛异常：
            // 提示词渲染失败会让整轮对话挂掉，代价远大于少一段约束
            block.append("\n所有步骤已结束，请直接汇总结果并收尾。");
            return truncate(block.toString());
        }

        block.append("\n当前步骤：第 ").append(current.seq()).append(" 步「").append(current.title()).append("」\n");
        if (current.detail() != null && !current.detail().isBlank()) {
            block.append("完成标准：").append(current.detail()).append('\n');
        }
        block.append("""
                执行要求：
                - 只推进这一步骤；在它完成之前不要开始后面的步骤
                - 完成后调用 completePlanStep(%d)，把这一步的产出写进 summary
                - 失败调用 failPlanStep(%d)；不要用同样的参数反复重试
                - 需要换路线时调用 revisePlan，而不是在计划之外自行其是
                """.formatted(current.seq(), current.seq()));

        List<PlanStepDto> remaining = steps.stream()
                .filter(step -> step.seq() > current.seq())
                .toList();
        if (!remaining.isEmpty()) {
            block.append("\n后续步骤：");
            remaining.stream()
                    .limit(MAX_LISTED_REMAINING)
                    .forEach(step -> block.append(step.seq()).append(' ').append(step.title()).append("；"));
            if (remaining.size() > MAX_LISTED_REMAINING) {
                block.append("等共 ").append(remaining.size()).append(" 步");
            } else {
                // 去掉末尾多余的分号
                block.setLength(block.length() - 1);
            }
            block.append('\n');
        }

        block.append("\n计划状态只能通过计划工具改变：不要在回答里声称「已完成第 N 步」却没有调用工具。");
        return truncate(block.toString());
    }

    /**
     * 当前应推进的步骤：优先正在跑的那一步，否则第一个未到终态的步骤。
     *
     * <p>「优先 RUNNING」很重要：模型可能同时开了两步（先 start 再回头补），
     * 此时约束应指向它真正在做的那一步，而不是序号最小的那一步。
     */
    private PlanStepDto currentStep(List<PlanStepDto> steps) {
        for (PlanStepDto step : steps) {
            if ("RUNNING".equals(step.status())) {
                return step;
            }
        }
        for (PlanStepDto step : steps) {
            if (!"DONE".equals(step.status()) && !"SKIPPED".equals(step.status())) {
                return step;
            }
        }
        return null;
    }

    /** 截断到配置上限：超长会把记忆与知识库挤出去，而它们同样影响回答质量 */
    private String truncate(String text) {
        int max = properties.maxInjectChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 12)) + "\n……（已截断）";
    }
}
