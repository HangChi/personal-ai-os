package com.personalos.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 助手消息的知识库引用快照（阶段 19）。
 *
 * <p>与 {@link ChatMessageEntity} 一样**不**建模 {@code @ManyToOne} 关联：
 * 只需要 message_id 这一列，级联删除由数据库外键负责（V11），
 * 引入关联只会带来懒加载风险而没有收益。
 *
 * <p>文档来源字段（document_id / document_title / filename / content）全部是**快照**：
 * 知识文档删除后历史引用仍可读——引用证明的是「回答当时看到了什么」。
 */
@Entity
@Table(name = "chat_message_citation")
public class ChatMessageCitationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 被引用的助手消息 */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 回答正文中的引用编号 [n]，从 1 开始 */
    @Column(name = "citation_index", nullable = false)
    private Integer citationIndex;

    /** 来源文档标识（快照，无外键） */
    @Column(name = "document_id", length = 64, nullable = false)
    private String documentId;

    /** 片段在文档内的序号（0 起） */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "document_title", length = 200, nullable = false)
    private String documentTitle;

    @Column(name = "filename", length = 255, nullable = false)
    private String filename;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 供 JPA 使用 */
    protected ChatMessageCitationEntity() {
    }

    public ChatMessageCitationEntity(Long messageId,
                                     Integer citationIndex,
                                     String documentId,
                                     Integer chunkIndex,
                                     String documentTitle,
                                     String filename,
                                     String content,
                                     Instant createdAt) {
        this.messageId = messageId;
        this.citationIndex = citationIndex;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.documentTitle = documentTitle;
        this.filename = filename;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Integer getCitationIndex() {
        return citationIndex;
    }

    public String getDocumentId() {
        return documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public String getFilename() {
        return filename;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
