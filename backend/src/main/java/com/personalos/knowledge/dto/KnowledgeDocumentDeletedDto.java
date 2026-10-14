package com.personalos.knowledge.dto;

/**
 * 删除文档的应答。
 *
 * @param deleted       是否已删除
 * @param deletedChunks 实际清理掉的向量条数（便于用户核对「删干净了没有」）
 */
public record KnowledgeDocumentDeletedDto(boolean deleted, int deletedChunks) {
}
