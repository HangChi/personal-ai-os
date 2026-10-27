package com.personalos.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一轮对话的模型用量（V15）。
 *
 * <p>与 {@link ChatMessageEntity} 一样**不建关联**：本模块只需要会话标识这一列，
 * 而且这里是刻意不建外键的——删会话不该抹掉用量历史（见迁移脚本注释）。
 *
 * <p>字段全部不可变：这是一条「已经发生的事实」，没有更新入口，
 * 因此连 setter 都不提供，只留构造器。
 */
@Entity
@Table(name = "token_usage")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    /** 模型名快照；可能为空（provider 未上报模型名） */
    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private TokenUsageSource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 供 JPA 使用 */
    protected TokenUsageEntity() {
    }

    public TokenUsageEntity(String conversationId, String model, int promptTokens, int completionTokens,
                            TokenUsageSource source, Instant createdAt) {
        this.conversationId = conversationId;
        this.model = model;
        // 上游个别实现会返回 null，落库前收敛为非负整数：
        // CHECK 约束会让 null 直接失败，而一轮用量缺数字不该导致写入整批回滚
        this.promptTokens = Math.max(promptTokens, 0);
        this.completionTokens = Math.max(completionTokens, 0);
        this.source = source;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public TokenUsageSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 本行合计 */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
