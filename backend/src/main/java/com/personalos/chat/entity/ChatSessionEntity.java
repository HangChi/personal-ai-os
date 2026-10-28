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

    /**
     * 该会话选定的对话模型名（V23）。
     *
     * <p>{@code null} 表示**沿用当前生效配置的默认模型**。三种情形要分清：
     * <ul>
     *   <li>{@code null} —— 从未选过；换生效配置时自动跟着变，这是绝大多数会话；</li>
     *   <li>非空 —— 用户显式选过；固定下来，不受换配置影响；</li>
     *   <li>非空但不在当前清单里 —— 用户后来把那个模型从清单删了。
     *       仍然保留：这条会话当时确实用它聊过，抹掉会让历史失真。</li>
     * </ul>
     */
    @Column(name = "chat_model", length = 120)
    private String chatModel;

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

    /**
     * 该会话选定的模型名；null 表示沿用生效配置的默认模型。
     *
     * <p><b>不更新 {@code updatedAt}</b>：换模型不是一次对话活动，
     * 与改名的语义相同（见 {@code ChatSessionService#rename} 的说明）。
     */
    public String getChatModel() {
        return chatModel;
    }

    /**
     * 设置会话模型。传 null / 空白表示「改回沿用默认」，而不是「记下空字符串」
     * —— 后者会让「没选过」与「选过又清空」在库里长得一样，而前者才是恢复默认。
     */
    public void setChatModel(String chatModel) {
        this.chatModel = chatModel == null || chatModel.isBlank() ? null : chatModel.strip();
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

    /**
     * 消息被回退（重新生成 / 编辑重发）后调用：摘要可能包含已删除的内容。
     *
     * <p>不做「把边界降到 fromSeq-1」那种精细回退 —— 摘要是一段连续文字，
     * 无法从中精确删掉某几条消息贡献的信息。边界一旦覆盖到被删区间，
     * 唯一安全的做法是整段作废，让下一轮重新生成。
     */
    public void invalidateSummaryFrom(int fromSeq) {
        if (summaryUptoSeq != null && summaryUptoSeq >= fromSeq) {
            contextSummary = null;
            summaryUptoSeq = 0;
        }
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
