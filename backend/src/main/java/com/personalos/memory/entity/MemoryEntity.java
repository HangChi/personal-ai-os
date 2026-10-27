package com.personalos.memory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 长期记忆：用户显式要求助手记住的一件事。
 *
 * <p>与本项目「会话内记忆」（{@code chat.memory.JpaChatMemory}）的区别：
 * 那条线解决的是**一个会话内部**的上下文顺序；本实体解决的是**跨会话**的约束，
 * 换哪个会话都应当生效。
 *
 * <p>刻意只有 {@code content} 一个业务字段：分类、标签、来源会话都没有消费方，
 * 加了也只会成为需要维护的空字段（见 specs/008-long-term-memory/research.md R-02）。
 */
@Entity
@Table(name = "memory")
public class MemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    /** 记忆原文；落库前已归一（去首尾空白），长度由 Service 层保证 */
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * 内容 SHA-256（小写 hex，V17）：去重的唯一键。
     *
     * <p>与 {@code content} 同步维护（构造器与 {@link #setContent} 都重算）。
     * 数据库有 {@code uk_memory_content_hash} 唯一约束，并发重复写入会在约束处失败，
     * 由 Service 层捕获后转为「返回既有条目」——把「不重复」从约定变成硬约束。
     */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最近修改时间。注入与列表都按它倒序，用户因此能预期「哪几条先生效」 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 来源（V13）：区分「用户显式保存」与「模型自动沉淀」。
     *
     * <p>存字符串而非 ordinal：枚举顺序一旦调整，ordinal 会静默指向另一个值。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private MemorySource source = MemorySource.MANUAL;

    /** 供 JPA 使用 */
    protected MemoryEntity() {
    }

    public MemoryEntity(String content, Instant now) {
        this(content, now, MemorySource.MANUAL);
    }

    public MemoryEntity(String content, Instant now, MemorySource source) {
        this.content = content;
        this.contentHash = hash(content);
        this.createdAt = now;
        this.updatedAt = now;
        this.source = source == null ? MemorySource.MANUAL : source;
    }

    public UUID getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public MemorySource getSource() {
        return source;
    }

    public void setContent(String content) {
        this.content = content;
        this.contentHash = hash(content);
    }

    /** 记录一次修改：刷新 updated_at，created_at 保持不变 */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    /** 内容哈希（与 V17 回填 SQL 的 encode(digest(content,'sha256'),'hex') 口径一致） */
    private static String hash(String content) {
        return com.personalos.common.json.JsonCodec.sha256Hex(content);
    }
}
