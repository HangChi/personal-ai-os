package com.personalos.common.idempotency;

/**
 * 一次操作的最终结果状态。
 *
 * <p>区分这些状态是「工具执行日志」能否用于审计的关键：
 * 若只记录成功，就无法回答「助手到底尝试过什么、被拒绝了什么、哪些其实没执行」。
 */
public enum IdempotencyOutcome {

    /** 已占位、正在执行（并发窗口内的中间态） */
    IN_PROGRESS,

    /** 真实执行成功 */
    SUCCESS,

    /** 已生成待人工确认的操作，**尚未执行** */
    REQUIRES_CONFIRMATION,

    /** 入参校验未通过，未执行 */
    REJECTED,

    /** 命中幂等，未重复执行 */
    DUPLICATE,

    /** 执行过程中报错 */
    FAILED
}
