package com.personalos.calendar.entity;

/**
 * 重复规则类型。
 *
 * <p>刻意只支持这四类：完整的 RRULE（{@code ical4j} 等）要引入新依赖并处理
 * 时区、夏令时、{@code BYSETPOS} 等复杂语义，对本地个人日程是明显的过度设计。
 */
public enum RecurrenceType {
    /** 不重复 */
    NONE,
    /** 每天同一时刻 */
    DAILY,
    /** 每周同一星期几、同一时刻 */
    WEEKLY,
    /** 每月同一「日」同一时刻；当月无该日时取当月最后一天 */
    MONTHLY,
    /** 周一至周五同一时刻 */
    WEEKDAYS
}
