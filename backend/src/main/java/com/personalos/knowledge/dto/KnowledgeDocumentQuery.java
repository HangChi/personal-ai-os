package com.personalos.knowledge.dto;

/**
 * 知识库文档列表查询（阶段 16）。
 *
 * <p>收敛口径与 {@code TaskQuery} / {@code ChatSessionQuery} 一致（非正 size → 20，
 * 超过 100 → 100，空关键词等同不筛）。
 *
 * @param keyword 标题或文件名关键词，可空
 * @param page    页码，从 0 开始；负值收敛为 0
 * @param size    每页条数
 */
public record KnowledgeDocumentQuery(String keyword, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public KnowledgeDocumentQuery {
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
