package com.personalos.knowledge.dto;

import java.util.UUID;

/**
 * 检索命中。
 *
 * <p>字段全部来自向量元数据，因此一次检索即可返回完整的来源信息，无需回表（避免 N+1）。
 *
 * @param documentId    来源文档标识
 * @param documentTitle 来源文档标题
 * @param filename      来源文件名
 * @param chunkIndex    片段在文档内的序号
 * @param content       片段原文
 * @param score         **向量余弦相似度**（越大越相关）。
 *                      混合检索下，仅由关键词通道命中、未经向量通道的片段为 {@code null} ——
 *                      它没有做过嵌入，不能编一个分数出来。
 * @param matchedBy     命中来源：{@code VECTOR} / {@code KEYWORD} / {@code BOTH}
 */
public record KnowledgeHitDto(UUID documentId,
                              String documentTitle,
                              String filename,
                              int chunkIndex,
                              String content,
                              Double score,
                              MatchedBy matchedBy) {

    /** 命中来源（2026-10-26 混合检索引入） */
    public enum MatchedBy {
        /** 仅向量通道命中 */
        VECTOR,
        /** 仅关键词通道命中（此时 score 为 null） */
        KEYWORD,
        /** 两个通道都命中，可信度最高 */
        BOTH
    }

    /** 兼容既有调用点：未指定来源时按纯向量处理 */
    public KnowledgeHitDto(UUID documentId, String documentTitle, String filename,
                           int chunkIndex, String content, Double score) {
        this(documentId, documentTitle, filename, chunkIndex, content, score, MatchedBy.VECTOR);
    }
}
