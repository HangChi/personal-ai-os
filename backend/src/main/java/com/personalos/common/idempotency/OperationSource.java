package com.personalos.common.idempotency;

/**
 * 操作来源。
 *
 * <p>用于在 {@code operation_record} 中区分「模型经由工具发起」与「客户端直接 HTTP 发起」。
 */
public enum OperationSource {
    /** Agent 工具调用 */
    TOOL,
    /** 客户端直接发起的 HTTP 请求 */
    HTTP
}
