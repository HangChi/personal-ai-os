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
 * @param score         相似度（越大越相关）
 */
public record KnowledgeHitDto(UUID documentId,
                              String documentTitle,
                              String filename,
                              int chunkIndex,
                              String content,
                              Double score) {
}
