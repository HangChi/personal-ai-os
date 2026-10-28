package com.personalos.agent.plan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 计划的单个步骤。
 *
 * <p>{@code toolName} / {@code toolResult} 由**工具执行外壳**回填，不由模型自报 ——
 * 模型自报「我调用了 createTask」可能与实际调用不一致（甚至什么都没调），
 * 而计划面板的价值恰恰是「实际做了什么」。
 */
@Entity
@Table(name = "agent_plan_step")
public class AgentPlanStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** 从 1 开始；稳定标识，工具与前端都用它引用步骤 */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StepStatus status = StepStatus.PENDING;

    /** 该步的验收说明（模型写）：下一轮据此判断「这一步算不算做完」 */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** 实际调用到的工具名 */
    @Column(name = "tool_name", length = 120)
    private String toolName;

    /** 工具结果摘要（截断存储；完整结果在工具审计表里） */
    @Column(name = "tool_result", columnDefinition = "text")
    private String toolResult;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 供 JPA 使用 */
    protected AgentPlanStepEntity() {
    }

    public AgentPlanStepEntity(UUID planId, int seq, String title, String detail) {
        this.planId = planId;
        this.seq = seq;
        this.title = title;
        this.detail = detail;
        this.status = StepStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public int getSeq() {
        return seq;
    }

    public String getTitle() {
        return title;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolResult() {
        return toolResult;
    }

    public String getError() {
        return error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** 开始执行。已 FAILED 的步骤可以再次开始（重试），已完成的不能 */
    public void start(Instant now) {
        this.status = StepStatus.RUNNING;
        this.startedAt = now;
        this.finishedAt = null;
        this.error = null;
    }

    public void complete(Instant now) {
        this.status = StepStatus.DONE;
        this.finishedAt = now;
        this.error = null;
    }

    public void fail(String reason, Instant now) {
        this.status = StepStatus.FAILED;
        this.finishedAt = now;
        this.error = reason;
    }

    public void skip(String reason, Instant now) {
        this.status = StepStatus.SKIPPED;
        this.finishedAt = now;
        this.error = reason;
    }

    /**
     * 绑定一次工具调用。
     *
     * <p>只记第一次：一个步骤可能包含多次工具调用（先查再改），
     * 若每次都覆盖，面板上就只剩最后一次 —— 而用户想知道的是「这一步动了什么」。
     * 后续调用追加成「工具名 + 摘要」的行，超过存储上限时截断。
     */
    public void bindTool(String name, String resultSummary, int maxResultChars) {
        if (this.toolName == null) {
            this.toolName = name;
            this.toolResult = truncate(resultSummary, maxResultChars);
            return;
        }
        String line = name + "：" + truncate(resultSummary, maxResultChars);
        String merged = this.toolResult == null || this.toolResult.isBlank() ? line : this.toolResult + "\n" + line;
        this.toolResult = truncate(merged, maxResultChars * 2);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
