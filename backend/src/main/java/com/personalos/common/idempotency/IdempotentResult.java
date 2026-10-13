package com.personalos.common.idempotency;

/**
 * 幂等执行的结果。
 *
 * @param responsePayload 序列化后的响应体；重复请求时原样返回首次结果
 * @param outcome         最终状态
 * @param replayed        true 表示本次**未执行**，是命中幂等后回放的结果
 */
public record IdempotentResult(String responsePayload, IdempotencyOutcome outcome, boolean replayed) {

    public static IdempotentResult of(String responsePayload, IdempotencyOutcome outcome) {
        return new IdempotentResult(responsePayload, outcome, false);
    }

    public IdempotentResult asReplay() {
        return new IdempotentResult(responsePayload, IdempotencyOutcome.DUPLICATE, true);
    }
}
