package com.personalos.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 知识库文档元数据。
 *
 * <p>刻意**不**保存原文：只保留分块后的片段（见 data-model.md 偏差 D-01）。
 *
 * <p>不变量：{@code chunkCount} 必须等于该文档在 {@code vector_store} 中的向量条数（SC-001）。
 * 由入库流程在写入向量成功后回填。
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    @Column(name = "filename", length = 300, nullable = false)
    private String filename;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 供 JPA 使用 */
    protected KnowledgeDocumentEntity() {
    }

    public KnowledgeDocumentEntity(String title, String filename, String contentType,
                                   long sizeBytes, int charCount, Instant now) {
        this.title = title;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.charCount = charCount;
        this.chunkCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 回填分块数（在向量写入成功之后调用，保证不变量成立）。
     */
    public void updateChunkCount(int chunkCount, Instant now) {
        this.chunkCount = chunkCount;
        this.updatedAt = now;
    }
}
