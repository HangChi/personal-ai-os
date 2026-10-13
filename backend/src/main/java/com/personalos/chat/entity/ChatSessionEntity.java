package com.personalos.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 聊天会话。
 *
 * <p>{@code conversationId} 既是主键，也是 Spring AI 的会话上下文标识，
 * 因此它天然就是会话隔离的边界（FR-008 / US4）。
 */
@Entity
@Table(name = "chat_session")
public class ChatSessionEntity {

    @Id
    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 供 JPA 使用 */
    protected ChatSessionEntity() {
    }

    public ChatSessionEntity(String conversationId, String title, Instant createdAt, Instant updatedAt) {
        this.conversationId = conversationId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
