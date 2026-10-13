package com.personalos.agent.confirmation;

/**
 * 需要人工确认的操作类型。
 *
 * <p>每个取值都对应一种「不可逆或影响用户计划」的动作（FR-007/008/009）。
 */
public enum ConfirmationAction {
    /** 删除任务（可含批量） */
    DELETE_TASK,
    /** 修改截止时间 */
    SCHEDULE_TASK,
    /** 修改优先级 */
    SET_PRIORITY
}
