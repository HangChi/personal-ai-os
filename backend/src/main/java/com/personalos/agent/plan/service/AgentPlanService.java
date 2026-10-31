package com.personalos.agent.plan.service;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.dto.PlanStepDto;
import com.personalos.agent.plan.entity.AgentPlanEntity;
import com.personalos.agent.plan.entity.AgentPlanStepEntity;
import com.personalos.agent.plan.entity.PlanStatus;
import com.personalos.agent.plan.entity.StepStatus;
import com.personalos.agent.plan.repository.AgentPlanRepository;
import com.personalos.agent.plan.repository.AgentPlanStepRepository;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.text.Texts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 计划的编排与状态机。
 *
 * <h2>为什么需要一张表，而不是让模型在提示词里「记得自己的计划」</h2>
 * 上下文窗口只带最近若干条消息，且工具结果一大就会被裁剪 —— 计划一旦滑出窗口，
 * 模型就真的忘了还剩几步。落到表里之后，计划成为**可读、可查、可断点续**的状态：
 * 前端能显示进度，模型能通过工具自检，失败时也能定位到具体步骤。
 *
 * <h2>三条状态机规则</h2>
 * <ol>
 *   <li><b>同一会话至多一个 ACTIVE 计划</b>：靠 DB 部分唯一索引保证。
 *       新建计划前必须先把旧的置 ABORTED 并 flush —— JPA 不保证先发 UPDATE 后发 INSERT
 *       （与模型配置「至多一行 active」是同一个坑）。</li>
 *   <li><b>步骤状态只能按图转移</b>：DONE/SKIPPED 是终态；FAILED 可以通过 start 重试。
 *       非法转移直接拒绝而不是静默纠正 —— 静默纠正会让面板显示与模型认知长期不一致。</li>
 *   <li><b>全部步骤到终态 ⇒ 计划自动完成</b>：否则计划永远「进行中」，</li>
 * </ol>
 *
 * <h2>工具结果为什么由外壳回填</h2>
 * {@link #bindToolCall} 由 {@code ToolExecutor} 在每次工具成功后调用，而不是让模型
 * 自己「汇报我调用了什么」。模型的自报可能与事实不符（漏报、错报工具名），
 * 而计划面板的全部价值就是「实际发生了什么」。
 */
@Service
public class AgentPlanService {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanService.class);

    /** 步骤数上限：超过这个数量的计划已经不是「计划」，是愿望清单 */
    public static final int MAX_STEPS = 12;

    /** 步骤标题长度上限 */
    public static final int MAX_TITLE_LENGTH = 200;

    /** 目标复述长度上限 */
    public static final int MAX_GOAL_LENGTH = 500;

    /** 单条工具结果摘要的存储上限（完整结果在工具审计表里） */
    private static final int MAX_TOOL_RESULT_CHARS = 400;

    private final AgentPlanRepository planRepository;
    private final AgentPlanStepRepository stepRepository;

    public AgentPlanService(AgentPlanRepository planRepository, AgentPlanStepRepository stepRepository) {
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
    }

    /**
     * 新建计划；已有的 ACTIVE 计划被置为 ABORTED。
     *
     * @param conversationId 会话
     * @param goal           目标的一句话复述
     * @param stepTitles     有序步骤标题
     * @return 新计划
     */
    @Transactional
    public PlanDto createPlan(String conversationId, String goal, List<String> stepTitles) {
        String normalizedGoal = Texts.requireNotBlank(goal, "计划目标", MAX_GOAL_LENGTH);
        List<String> titles = validatedTitles(stepTitles);

        // 先让位再插入：唯一索引是「每会话至多一个 ACTIVE」，
        // 不先 flush 就插新的，两条 UPDATE/INSERT 的发送顺序由 Hibernate 决定
        abortActivePlan(conversationId);

        Instant now = Instant.now();
        AgentPlanEntity plan = planRepository.saveAndFlush(new AgentPlanEntity(conversationId, normalizedGoal, now));
        List<AgentPlanStepEntity> steps = new ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            steps.add(new AgentPlanStepEntity(plan.getId(), i + 1, titles.get(i), null));
        }
        stepRepository.saveAll(steps);

        log.info("创建执行计划 conversationId={} 步骤数={} goal={}", conversationId, steps.size(), normalizedGoal);
        return toDto(plan, steps);
    }

    /**
     * 开始某一步。
     *
     * <p><b>阶段 23 起只允许推进「第一个未结束的步骤」</b>。这是把计划从记录层
     * 抬到控制层的关键一步：只要还能随便跳到第 5 步，「按序执行」就只是提示词里
     * 的一句客气话。跳步被拒绝时给出「先做第几 步」，模型据此能自己纠正 ——
     * 拒绝而不解释会让它反复重试同一个调用。
     *
     * <p>例外（仍然允许）：本步已在 RUNNING（幂等返回）、本步是 FAILED 且排在
     * 第一个未结束位置（重试）。
     *
     * @param conversationId 会话
     * @param seq            步骤序号
     * @return 最新计划
     */
    @Transactional
    public PlanDto startStep(String conversationId, int seq) {
        AgentPlanEntity plan = requireActivePlan(conversationId);
        AgentPlanStepEntity step = requireStep(plan, seq);

        if (step.getStatus() == StepStatus.RUNNING) {
            return toDto(plan); // 幂等：模型重复调用不该报错
        }
        if (step.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "步骤 " + seq + " 已结束（" + step.getStatus() + "），如需重做请先说明原因并重规划");
        }

        int nextSeq = firstOpenSeq(plan);
        if (nextSeq > 0 && seq != nextSeq) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "计划必须按序推进：第 " + nextSeq + " 步还没结束，不能开始第 " + seq + " 步。"
                            + "请先执行第 " + nextSeq + " 步，或调用 revisePlan 换一条路线");
        }

        Instant now = Instant.now();
        step.start(now);
        stepRepository.save(step);
        plan.touch(now);
        planRepository.save(plan);
        log.info("计划 {} 步骤 {} 开始：{}", plan.getId(), seq, step.getTitle());
        return toDto(plan);
    }

    /**
     * 完成某一步。
     *
     * <p><b>阶段 23 起要求「产出证据」</b>：这一步至少绑定过一次工具调用，
     * 或者调用方给出了非空 summary。两者都没有时拒绝。
     *
     * <p>为什么这条约束重要：模型最常见的虚假完成是「我已经查过了，结果如下」
     * 然后直接进下一步 —— 而它其实什么都没调。加入校验后，要么真的调了工具
     * （工具名由执行外壳回填，无法伪造），要么至少写下一句可核对的结论。
     * 这不是完美校验（模型可以编造 summary），但它把「零成本的空口完成」堵掉了。
     *
     * @param conversationId 会话
     * @param seq            步骤序号
     * @param summary        这一步的结论（可空）；非空时追加到工具结果摘要里
     * @return 最新计划
     */
    @Transactional
    public PlanDto completeStep(String conversationId, int seq, String summary) {
        AgentPlanEntity plan = requireActivePlan(conversationId);
        AgentPlanStepEntity step = requireStep(plan, seq);

        if (step.getStatus() == StepStatus.DONE) {
            return toDto(plan);
        }
        if (step.getStatus() == StepStatus.SKIPPED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "步骤 " + seq + " 已被跳过，不能再标记完成");
        }
        int nextSeq = firstOpenSeq(plan);
        if (seq != nextSeq) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "计划必须按序推进：请先完成第 " + nextSeq + " 步，不能直接完成第 " + seq + " 步");
        }
        if (step.getStatus() == StepStatus.FAILED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "失败步骤必须先重启或显式跳过，不能直接标记完成");
        }
        if (step.getStatus() == StepStatus.PENDING) {
            // 允许但不推荐：模型可能跳过 start 直接 complete。补一个 startedAt，
            // 让「耗时」这个字段不会出现「有结束没开始」的缺口
            log.debug("步骤 {} 未经 start 直接完成，补记开始时间", seq);
            step.start(Instant.now());
        }

        boolean hasEvidence = step.getToolName() != null || (summary != null && !summary.isBlank());
        if (!hasEvidence) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "第 " + seq + " 步没有任何实际动作或结论，不能标记完成。"
                            + "请先调用完成这一步所需的工具，或在 summary 里写下可核对的结果");
        }

        Instant now = Instant.now();
        step.complete(now);
        if (summary != null && !summary.isBlank()) {
            step.bindTool("结论", summary.strip(), MAX_TOOL_RESULT_CHARS);
        }
        stepRepository.save(step);
        plan.touch(now);

        finishIfAllStepsDone(plan, now);
        planRepository.save(plan);
        log.info("计划 {} 步骤 {} 完成", plan.getId(), seq);
        return toDto(plan);
    }

    /**
     * 标记某一步失败。
     *
     * <p>不自动重规划：重规划是**有代价的决策**（换路线意味着前面的部分工作白做），
     * 应当由模型结合失败原因判断，而不是系统替它决定。
     *
     * @param conversationId 会话
     * @param seq            步骤序号
     * @param reason         失败原因（会给模型看，也记在面板上）
     * @return 最新计划
     */
    @Transactional
    public PlanDto failStep(String conversationId, int seq, String reason) {
        AgentPlanEntity plan = requireActivePlan(conversationId);
        AgentPlanStepEntity step = requireStep(plan, seq);
        if (step.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "步骤 " + seq + " 已结束（" + step.getStatus() + "），不能标记失败");
        }

        Instant now = Instant.now();
        step.fail(reason == null || reason.isBlank() ? "未说明原因" : reason.strip(), now);
        stepRepository.save(step);
        plan.touch(now);
        planRepository.save(plan);
        log.info("计划 {} 步骤 {} 失败：{}", plan.getId(), seq, reason);
        return toDto(plan);
    }

    /** 跳过某一步（例如依赖的数据不存在）。跳过计入「已处理」，计划仍可正常完成 */
    @Transactional
    public PlanDto skipStep(String conversationId, int seq, String reason) {
        AgentPlanEntity plan = requireActivePlan(conversationId);
        AgentPlanStepEntity step = requireStep(plan, seq);
        if (step.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "步骤 " + seq + " 已结束（" + step.getStatus() + "），不能跳过");
        }

        Instant now = Instant.now();
        step.skip(reason == null || reason.isBlank() ? "未说明原因" : reason.strip(), now);
        stepRepository.save(step);
        plan.touch(now);
        finishIfAllStepsDone(plan, now);
        planRepository.save(plan);
        log.info("计划 {} 步骤 {} 跳过：{}", plan.getId(), seq, reason);
        return toDto(plan);
    }

    /**
     * 重规划：放弃当前计划，按新的步骤重新开始。
     *
     * <p>旧计划保留为 ABORTED 而不是删除 —— 用户需要看到「原来打算怎么做、
     * 走到哪一步、为什么换路」。这也是把重规划做成新记录而不是就地改步骤的理由：
     * 就地改会抹掉这条线索。
     *
     * @param conversationId 会话
     * @param reason         重规划原因（记日志；计划面板通过新旧两条记录体现）
     * @param goal           新的目标复述；空则沿用原目标
     * @param stepTitles     新步骤
     * @return 新计划
     */
    @Transactional
    public PlanDto revisePlan(String conversationId, String reason, String goal, List<String> stepTitles) {
        AgentPlanEntity previous = planRepository
                .findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE)
                .orElse(null);
        String effectiveGoal = goal;
        if (effectiveGoal == null || effectiveGoal.isBlank()) {
            effectiveGoal = previous == null ? null : previous.getGoal();
        }
        log.info("重规划 conversationId={} 原因={} 旧计划={}",
                conversationId, reason, previous == null ? "（无）" : previous.getId());
        // createPlan 会自行把旧的 ACTIVE 置 ABORTED
        return createPlan(conversationId, effectiveGoal, stepTitles);
    }

    /** 当前进行中的计划（无则空） */
    @Transactional(readOnly = true)
    public Optional<PlanDto> activePlan(String conversationId) {
        return planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE)
                .map(this::toDto);
    }

    /**
     * 最近一个计划（不论状态）。计划面板用它：任务结束后仍要显示「结果如何」。
     *
     * @param conversationId 会话
     * @return 最近计划；从未规划过则为空
     */
    @Transactional(readOnly = true)
    public Optional<PlanDto> latestPlan(String conversationId) {
        return planRepository.findFirstByConversationIdOrderByCreatedAtDesc(conversationId)
                .map(this::toDto);
    }

    /**
     * 把一次工具调用绑定到「当前正在执行的步骤」。
     *
     * <p>由工具执行外壳调用，**绝不抛异常**：绑不上（没有计划、没有 RUNNING 步骤）
     * 是完全正常的 —— 绝大多数对话根本不涉及计划，工具该照常执行完。
     *
     * @param conversationId 会话
     * @param toolName       工具名
     * @param resultSummary  结果摘要（调用方已截断）
     */
    @Transactional
    public void bindToolCall(String conversationId, String toolName, String resultSummary) {
        try {
            Optional<AgentPlanEntity> activePlan = planRepository
                    .findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE);
            if (activePlan.isEmpty()) {
                return;
            }
            Optional<AgentPlanStepEntity> runningStep = stepRepository
                    .findFirstByPlanIdAndStatusOrderBySeqAsc(activePlan.get().getId(), StepStatus.RUNNING);
            if (runningStep.isEmpty()) {
                return;
            }
            AgentPlanStepEntity step = runningStep.get();
            step.bindTool(toolName, resultSummary, MAX_TOOL_RESULT_CHARS);
            stepRepository.save(step);

            Instant now = Instant.now();
            activePlan.get().touch(now);
            planRepository.save(activePlan.get());
        } catch (RuntimeException ex) {
            log.debug("工具调用绑定到计划步骤失败（不影响工具结果）：{}", ex.toString());
        }
    }

    /**
     * 工具失败时把当前步骤标记为 FAILED，并返回给模型的提示。
     *
     * <p>「自动标失败」而不是等模型自己报：模型在工具被拒后常常直接换个参数重试，
     * 若不自动标记，面板会一直显示「进行中」，用户看不出这一步其实已经出过问题。
     * 但**不自动重规划** —— 是否换路线由模型决定，提示里只给出两个选项。
     *
     * @param conversationId 会话
     * @param toolName       工具名
     * @param message        失败原因
     * @return 追加到工具结果末尾的提示；无进行中步骤时为空
     */
    @Transactional
    public Optional<String> onToolFailure(String conversationId, String toolName, String message) {
        try {
            Optional<AgentPlanEntity> plan = planRepository
                    .findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE);
            if (plan.isEmpty()) {
                return Optional.empty();
            }
            Optional<AgentPlanStepEntity> running = stepRepository
                    .findFirstByPlanIdAndStatusOrderBySeqAsc(plan.get().getId(), StepStatus.RUNNING);
            if (running.isEmpty()) {
                return Optional.empty();
            }
            AgentPlanStepEntity step = running.get();
            Instant now = Instant.now();
            step.fail(toolName + " 失败：" + (message == null ? "未说明原因" : message), now);
            stepRepository.save(step);
            plan.get().touch(now);
            planRepository.save(plan.get());
            log.info("计划 {} 步骤 {} 因工具 {} 失败而标记失败", plan.get().getId(), step.getSeq(), toolName);
            return Optional.of("步骤 " + step.getSeq() + " 已标记为失败。"
                    + "修正参数后调用 startPlanStep 重启这一步，或调用 revisePlan 换路线；不要重复同参数失败调用。");
        } catch (RuntimeException ex) {
            log.debug("工具失败回写计划步骤失败（忽略）：{}", ex.toString());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ 内部

    /** 把当前 ACTIVE 计划置为 ABORTED 并立即 flush，给新计划让出唯一索引 */
    private void abortActivePlan(String conversationId) {
        planRepository.findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE)
                .ifPresent(previous -> {
                    previous.abort(Instant.now());
                    // saveAndFlush：必须真的先发 UPDATE，否则新计划的 INSERT 会撞
                    // uk_agent_plan_active_per_conversation（两条语句的顺序不由我们决定）
                    planRepository.saveAndFlush(previous);
                    log.info("旧计划 {} 被取代（ABORTED）", previous.getId());
                });
    }

    private AgentPlanEntity requireActivePlan(String conversationId) {
        return planRepository
                .findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, PlanStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                        "当前会话没有进行中的计划，请先调用 createPlan"));
    }

    private AgentPlanStepEntity requireStep(AgentPlanEntity plan, int seq) {
        return stepRepository.findByPlanIdAndSeq(plan.getId(), seq)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "计划里没有第 " + seq + " 步（有效范围 1~" + stepRepository
                                .findByPlanIdOrderBySeqAsc(plan.getId()).size() + "）"));
    }

    /**
     * 第一个「未到终态」的步骤序号；没有则返回 -1。
     *
     * <p>它是「按序推进」的判据：只有它允许被 start。FAILED 也算未到终态 ——
     * 失败的那一步必须重试或跳过，不能直接跳到后面。
     */
    private int firstOpenSeq(AgentPlanEntity plan) {
        return stepRepository.findByPlanIdOrderBySeqAsc(plan.getId()).stream()
                .filter(step -> !step.getStatus().isTerminal())
                .mapToInt(AgentPlanStepEntity::getSeq)
                .findFirst()
                .orElse(-1);
    }

    /** 所有步骤都到终态时把计划标记完成 */
    private void finishIfAllStepsDone(AgentPlanEntity plan, Instant now) {
        List<AgentPlanStepEntity> steps = stepRepository.findByPlanIdOrderBySeqAsc(plan.getId());
        boolean allDone = !steps.isEmpty() && steps.stream().allMatch(step -> step.getStatus().isTerminal());
        if (allDone) {
            plan.complete(now);
            log.info("计划 {} 全部步骤结束，标记 COMPLETED", plan.getId());
        }
    }

    private List<String> validatedTitles(List<String> stepTitles) {
        if (stepTitles == null || stepTitles.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "计划至少需要一个步骤");
        }
        if (stepTitles.size() > MAX_STEPS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "计划步骤不能超过 " + MAX_STEPS + " 步，请先拆成更小的任务");
        }
        List<String> titles = new ArrayList<>(stepTitles.size());
        for (int i = 0; i < stepTitles.size(); i++) {
            titles.add(Texts.requireNotBlank(stepTitles.get(i), "第 " + (i + 1) + " 步标题", MAX_TITLE_LENGTH));
        }
        return titles;
    }

    private PlanDto toDto(AgentPlanEntity plan) {
        return toDto(plan, stepRepository.findByPlanIdOrderBySeqAsc(plan.getId()));
    }

    private PlanDto toDto(AgentPlanEntity plan, List<AgentPlanStepEntity> steps) {
        List<PlanStepDto> stepDtos = steps.stream()
                .map(step -> new PlanStepDto(step.getSeq(), step.getTitle(), step.getStatus().name(),
                        step.getDetail(), step.getToolName(), step.getToolResult(), step.getError(),
                        step.getStartedAt(), step.getFinishedAt()))
                .toList();
        return new PlanDto(plan.getId().toString(), plan.getConversationId(), plan.getGoal(),
                plan.getStatus().name(), stepDtos, plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
