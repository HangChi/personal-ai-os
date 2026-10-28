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
 * Agent 任务计划（一次多步任务的总体目标）。
 *
 * <p>步骤**不映射成 {@code @OneToMany}**：计划与步骤总是一起读、一起渲染，
 * 但步骤需要在工具执行路径上单独按状态查（「哪个步骤正在跑」），
 * 关联映射会引出懒加载与 N+1 两个坑，而收益只是少写一个仓储方法。
 */
@Entity
@Table(name = "agent_plan")
public class AgentPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    /** 所属会话。删会话时级联删除（外键 ON DELETE CASCADE） */
    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    /** 目标的一句话复述：计划面板标题、重规划时重新对齐都用它 */
    @Column(name = "goal", nullable = false, columnDefinition = "text")
    private String goal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlanStatus status = PlanStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 供 JPA 使用 */
    protected AgentPlanEntity() {
    }

    public AgentPlanEntity(String conversationId, String goal, Instant now) {
        this.conversationId = conversationId;
        this.goal = goal;
        this.status = PlanStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getGoal() {
        return goal;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == PlanStatus.ACTIVE;
    }

    /** 全部步骤走完（或被跳过） */
    public void complete(Instant now) {
        if (status == PlanStatus.COMPLETED) {
            return;
        }
        this.status = PlanStatus.COMPLETED;
        this.updatedAt = now;
    }

    /** 放弃：被新计划取代、或任务无法继续 */
    public void abort(Instant now) {
        if (status == PlanStatus.ABORTED) {
            return;
        }
        this.status = PlanStatus.ABORTED;
        this.updatedAt = now;
    }

    /** 步骤状态变化时刷新 updated_at，让「最近在动的计划」可排序 */
    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
