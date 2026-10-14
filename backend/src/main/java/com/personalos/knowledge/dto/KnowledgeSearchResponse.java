package com.personalos.knowledge.dto;

import java.util.List;

/**
 * 检索响应。
 *
 * @param query 实际使用的检索词
 * @param hits  命中片段，按相似度倒序；空列表表示没有达到阈值的片段（**正常结果**，不是错误）
 */
public record KnowledgeSearchResponse(String query, List<KnowledgeHitDto> hits) {
}
