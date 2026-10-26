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

    /**
     * 滚动摘要：已滑出上下文窗口的历史对话的压缩结果（V12）。
     *
     * <p>null 与空串语义不同：null = 从未生成过；空串 = 生成过但模型没给出内容。
     */
    @Column(name = "context_summary", columnDefinition = "text")
    private String contextSummary;

    /** 摘要已覆盖到的消息 sequence_no（含）；0 表示尚无摘要 */
    @Column(name = "summary_upto_seq", nullable = false)
    private Integer summaryUptoSeq = 0;

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

    public String getContextSummary() {
        return contextSummary;
    }

    public int getSummaryUptoSeq() {
        return summaryUptoSeq == null ? 0 : summaryUptoSeq;
    }

    /**
     * 更新滚动摘要及其覆盖边界。
     *
     * <p>边界只允许**前进**：乱序的异步任务（例如同一会话连续两轮都触发了摘要）
     * 若用旧结果覆盖新结果，会把已经压进去的内容重新变回「未摘要」。
     *
     * @param summary 新的摘要文本；空白视为「无摘要」
     * @param uptoSeq 已覆盖到的 sequence_no
     */
    public void applySummary(String summary, int uptoSeq) {
        if (uptoSeq <= getSummaryUptoSeq()) {
            return;
        }
        this.contextSummary = summary;
        this.summaryUptoSeq = uptoSeq;
    }
}
