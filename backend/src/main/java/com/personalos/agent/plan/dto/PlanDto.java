package com.personalos.agent.plan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划视图（对外契约，工具与 HTTP 共用）。
 *
 * @param id             计划标识
 * @param conversationId 所属会话
 * @param goal           目标复述
 * @param status         {@code ACTIVE} / {@code COMPLETED} / {@code ABORTED}
 * @param steps          按 seq 升序的步骤
 * @param createdAt      创建时间
 * @param updatedAt      最近一步变化的时间
 */
public record PlanDto(String id, String conversationId, String goal, String status,
                      List<PlanStepDto> steps, Instant createdAt, Instant updatedAt) {

    /** 已完成（或跳过）的步骤数，供面板显示进度 */
    public long doneCount() {
        return steps.stream()
                .filter(step -> "DONE".equals(step.status()) || "SKIPPED".equals(step.status()))
                .count();
    }
}
