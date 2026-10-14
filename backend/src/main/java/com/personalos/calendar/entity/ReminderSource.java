package com.personalos.calendar.entity;

/**
 * 提醒的来源类型。
 *
 * <p>用「来源类型 + 来源标识」的多态引用，让一张 {@code reminder} 表同时承载
 * 日程与任务两类提醒，避免两张几乎相同的表与前端侧的 UNION 合并。
 */
public enum ReminderSource {
    /** 来源是日程的一次发生 */
    CALENDAR_EVENT,
    /** 来源是任务的截止时间 */
    TASK
}
