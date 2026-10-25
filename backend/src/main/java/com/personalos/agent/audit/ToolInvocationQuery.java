package com.personalos.agent.audit;

import com.personalos.common.idempotency.IdempotencyOutcome;

/**
 * 工具调用审计的查询条件。
 *
 * <p>分页参数的收敛口径与 {@code TaskQuery} 一致（负页码归 0、非正 size 取默认、
 * 超过硬上限收敛到上限）——同一个「翻页」概念在两个模块里不该有两套边界行为。
 *
 * @param conversationId 只看某个会话的调用，可空
 * @param outcome        只看某种结果，可空表示不限
 * @param page           页码，从 0 开始；负值收敛为 0
 * @param size           每页条数；非正收敛为默认值，超过上限收敛为上限
 */
public record ToolInvocationQuery(String conversationId, IdempotencyOutcome outcome, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public ToolInvocationQuery {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        // 空串与纯空白等同于「不限」：调用方把表单里的空值原样传上来的情况很常见
        if (conversationId != null && conversationId.isBlank()) {
            conversationId = null;
        }
    }
}
