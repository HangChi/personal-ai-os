package com.personalos.common.confirmation;

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
 * 待人工确认的操作。
 *
 * <p>本实体是「模型不能自作主张」这条约束的落点：模型可以创建它，但**没有任何工具**能把它变成
 * {@link ConfirmationStatus#CONFIRMED} —— 那只能通过面向人类的接口。
 *
 * <p><b>为什么是通用的而不是任务专用</b>：确认机制（令牌 / 有效期 / 单次性 / 原子占位）
 * 与具体业务无关。表名从 {@code task_confirmation} 改为 {@code confirmation}，
 * 是因为它现在也承载日程删除等其它模块的高风险操作 —— 名字要跟得上事实。
 */
@Entity
@Table(name = "confirmation")
public class ConfirmationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    /** 一次性令牌；只经面向人类的接口下发，绝不返回给模型 */
    @Column(name = "token", length = 64, nullable = false)
    private String token;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 40, nullable = false)
    private ConfirmationAction action;

    /** 面向人类的可读描述；由服务端依据 payload 生成，不采信模型输出的文本 */
    @Column(name = "summary", length = 500, nullable = false)
    private String summary;

    /** 执行所需参数（JSON 文本）。含标题快照，使任务删除后仍可审计其内容 */
    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "affected_count", nullable = false)
    private Integer affectedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ConfirmationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 供 JPA 使用 */
    protected ConfirmationEntity() {
    }

    public ConfirmationEntity(String token, String conversationId, ConfirmationAction action,
                              String summary, String payload, Integer affectedCount,
                              Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.conversationId = conversationId;
        this.action = action;
        this.summary = summary;
        this.payload = payload;
        this.affectedCount = affectedCount;
        this.status = ConfirmationStatus.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getConversationId() {
        return conversationId;
    }

    public ConfirmationAction getAction() {
        return action;
    }

    public String getSummary() {
        return summary;
    }

    public String getPayload() {
        return payload;
    }

    public Integer getAffectedCount() {
        return affectedCount;
    }

    public ConfirmationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void markResolved(ConfirmationStatus newStatus, Instant resolvedAt) {
        this.status = newStatus;
        this.resolvedAt = resolvedAt;
    }
}
