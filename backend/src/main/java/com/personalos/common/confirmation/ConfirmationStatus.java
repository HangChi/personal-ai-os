package com.personalos.common.confirmation;

/**
 * 待确认操作的状态。
 *
 * <p>只有 {@link #PENDING} 可以被确认或取消；其余状态再次确认一律拒绝（FR-011）。
 *
 * <h2>{@link #FAILED} 为什么必须与 {@link #CANCELLED} 分开</h2>
 * 两者都是「没有产生变更」，但对用户的含义完全不同：
 * {@code CANCELLED} 是「你取消了」或「目标本来就没了」——用户自己知道发生了什么；
 * {@code FAILED} 是「系统没能执行」——用户以为点了就生效，必须被告知去重试。
 * 混成一类则失败被静默吸收，操作永久丢失（令牌一次性，用户连重试入口都没有）。
 */
public enum ConfirmationStatus {
    /** 待人类确认 */
    PENDING,
    /** 已被确认并执行 */
    CONFIRMED,
    /** 已被取消，或目标已不存在 */
    CANCELLED,
    /** 超过有效期 */
    EXPIRED,
    /**
     * 已确认但执行失败（如 MCP server 不可用、目标在占位后消失）。
     *
     * <p>占位（{@code CONFIRMED}）与执行不再同事务，执行失败时占位无法回滚，
     * 因此必须有这个状态如实记录结果。
     */
    FAILED
}
