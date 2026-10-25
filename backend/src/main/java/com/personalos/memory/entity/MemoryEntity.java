package com.personalos.memory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最近修改时间。注入与列表都按它倒序，用户因此能预期「哪几条先生效」 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 供 JPA 使用 */
    protected MemoryEntity() {
    }

    public MemoryEntity(String content, Instant now) {
        this.content = content;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /** 记录一次修改：刷新 updated_at，created_at 保持不变 */
    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
