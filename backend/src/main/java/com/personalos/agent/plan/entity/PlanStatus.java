package com.personalos.agent.plan.entity;

/**
 * 计划整体状态。
 *
 * <p>三态而不是「进行中 / 结束」两态：被新计划取代（ABORTED）与正常走完（COMPLETED）
 * 在界面上必须能区分 —— 后者代表目标达成，前者代表「换了条路」。
 */
public enum PlanStatus {

    /** 进行中。同一会话至多一个（DB 部分唯一索引保证） */
    ACTIVE,

    /** 全部步骤已完成或跳过 */
    COMPLETED,

    /** 被放弃：用户改主意、任务失败无法继续、或被重规划出的新计划取代 */
    ABORTED
}
