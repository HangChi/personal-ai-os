package com.personalos.common.confirmation;

/**
 * 待确认操作的状态。
 *
 * <p>只有 {@link #PENDING} 可以被确认或取消；其余状态再次确认一律拒绝（FR-011）。
 */
public enum ConfirmationStatus {
    /** 待人类确认 */
    PENDING,
    /** 已被确认并执行 */
    CONFIRMED,
    /** 已被取消，或目标已不存在 */
    CANCELLED,
    /** 超过有效期 */
    EXPIRED
}
