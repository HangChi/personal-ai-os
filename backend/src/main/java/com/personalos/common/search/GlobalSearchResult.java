package com.personalos.common.search;

import java.util.List;

/**
 * 跨模块全局搜索结果（阶段 17）。
 *
 * @param keyword   实际使用的关键词（trim 后）
 * @param totalHits 命中总数（各分组条数之和）
 * @param groups    有命中的模块分组，按模块名排序
 */
public record GlobalSearchResult(String keyword, int totalHits, List<SearchGroup> groups) {
}
