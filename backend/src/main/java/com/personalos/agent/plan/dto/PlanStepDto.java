package com.personalos.agent.plan.dto;

import java.time.Instant;

/**
 * 单个步骤的视图。
 *
 * @param seq        步骤序号（从 1 开始）
 * @param title      步骤标题
 * @param status     {@code PENDING} / {@code RUNNING} / {@code DONE} / {@code FAILED} / {@code SKIPPED}
 * @param detail     验收说明（模型写的「这一步算做完的标准」）
 * @param toolName   实际调用到的工具名（由执行外壳回填，非模型自报）
 * @param toolResult 工具结果摘要
 * @param error      失败原因；非空时面板提示可以重试或重规划
 * @param startedAt  开始时间
 * @param finishedAt 结束时间
 */
public record PlanStepDto(int seq, String title, String status, String detail, String toolName,
                          String toolResult, String error, Instant startedAt, Instant finishedAt) {
}
