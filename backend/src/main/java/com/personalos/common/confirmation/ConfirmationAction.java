package com.personalos.common.confirmation;

/**
 * 需要人工确认的操作类型。
 *
 * <p>每个取值都对应一种「不可逆或影响用户计划」的动作。
 *
 * <p><b>新增取值时必须一并提供处理器</b>：{@link ConfirmationRegistry} 会在启动期校验
 * 「每个取值都有 {@link ConfirmationActionHandler}」，否则应用起不来。
 * 与其等用户点了「确认」才发现没人处理，不如启动就拦住。
 * 另外 {@code confirmation.action} 上有 CHECK 约束，新增取值需要配套迁移。
 */
public enum ConfirmationAction {
    /** 删除任务（可含批量） */
    DELETE_TASK,
    /** 修改任务截止时间 */
    SCHEDULE_TASK,
    /** 修改任务优先级 */
    SET_PRIORITY
}
