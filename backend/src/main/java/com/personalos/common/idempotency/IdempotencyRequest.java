package com.personalos.common.idempotency;

/**
 * 幂等执行的输入描述。
 *
 * @param key            幂等键（调用方负责保证「同一次逻辑操作」用同一个键）
 * @param source         操作来源
 * @param operation      操作名（工具名，或 {@code POST /api/v1/tasks} 这类标识）
 * @param digest         请求参数摘要（SHA-256）
 * @param conversationId 所属会话，可空
 * @param turnId         所属轮次，可空。工具层幂等的范围边界
 */
public record IdempotencyRequest(String key,
                                 OperationSource source,
                                 String operation,
                                 String digest,
                                 String conversationId,
                                 String turnId) {
}
