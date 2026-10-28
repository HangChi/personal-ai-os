package com.personalos.agent.plan.entity;

/**
 * 单个步骤的状态。
 *
 * <p>转移规则（由 {@code AgentPlanService} 强制，非法转移一律拒绝而不是静默纠正）：
 * <pre>
 *   PENDING ──start──▶ RUNNING ──complete──▶ DONE
 *      │                  │
 *      │                  └──fail──▶ FAILED ──start──▶ RUNNING（允许重试）
 *      └──skip──▶ SKIPPED
 * </pre>
 *
 * <p>{@code FAILED} 可以重新 {@code start}：那是「重试这一步」，与「重规划」
 * （换一套步骤）是两件事，前者便宜得多。
 */
public enum StepStatus {

    /** 还没开始 */
    PENDING,

    /** 正在执行。同一计划同时只应有一个 RUNNING，工具结果按它归集 */
    RUNNING,

    /** 已完成 */
    DONE,

    /** 失败。保留在计划里而不是删掉：用户要看到「卡在哪一步」 */
    FAILED,

    /** 主动跳过（例如依赖的数据不存在） */
    SKIPPED;

    /** 是否已处于终态（不再变化，除非显式重试 FAILED） */
    public boolean isTerminal() {
        return this == DONE || this == SKIPPED;
    }
}
