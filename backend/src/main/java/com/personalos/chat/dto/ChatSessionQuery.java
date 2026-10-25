package com.personalos.chat.dto;

/**
 * 会话列表查询（阶段 16）。
 *
 * <p>参数收敛口径与 {@code TaskQuery} 完全一致：同一个「翻页」概念在四个模块里
 * 不该有两套边界行为，否则用户要在不同页面记不同规则。
 *
 * @param keyword 标题关键词，可空；空串等同于「不筛选」
 * @param page    页码，从 0 开始；负值收敛为 0
 * @param size    每页条数；非正收敛为默认值，超过上限收敛为上限
 */
public record ChatSessionQuery(String keyword, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public ChatSessionQuery {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }
    }
}
