package com.personalos.task.dto;

/**
 * 任务全量统计（审查 #16，供首页统计卡使用）。
 *
 * <p>存在的理由：首页的「待办 / 逾期」此前用**分页列表的派生值**（当前页最多 20 条）
 * 冒充全量——100 条待办时首页显示「≤20」，与任务页「共 100 条」自相矛盾。
 * 这里用一条聚合 SQL 给出真实全量数字，与分页无关、与筛选条件无关。
 *
 * @param pending 未完成任务数（状态非 DONE）
 * @param overdue 逾期未完成数（状态非 DONE 且截止时间早于当前时刻）
 */
public record TaskStatsDto(long pending, long overdue) {
}
