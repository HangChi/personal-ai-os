package com.personalos.calendar.entity;

/**
 * 提醒状态。
 *
 * <p>刻意**没有** {@code FIRED} 状态：提醒是否「到点」由 {@code remind_at <= now} 判定，
 * 而不是靠一次状态跃迁。这样「已过点但未读」的提醒天然仍可展示 ——
 * 提醒的意义在于「你还没看到它」，而不是「它恰好在这一秒到点」。
 */
public enum ReminderStatus {
    /** 待提醒（含已过点但未读的） */
    PENDING,
    /** 已读 */
    READ,
    /** 已取消（来源被删除等） */
    CANCELLED
}
