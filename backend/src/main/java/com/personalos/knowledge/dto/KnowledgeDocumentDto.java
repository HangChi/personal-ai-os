package com.personalos.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 知识文档视图。
 *
 * @param id          文档标识
 * @param title       标题
 * @param filename    原始文件名
 * @param contentType 内容类型
 * @param sizeBytes   原始字节数
 * @param charCount   解析后的字符数
 * @param chunkCount  分块数（等于该文档的向量条数）
 * @param createdAt   上传时间
 */
public record KnowledgeDocumentDto(UUID id,
                                   String title,
                                   String filename,
                                   String contentType,
                                   long sizeBytes,
                                   int charCount,
                                   int chunkCount,
                                   Instant createdAt) {
}
