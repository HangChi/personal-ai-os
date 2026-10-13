package com.personalos.common.idempotency;

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
 * 操作记录：同时承担「工具执行审计」与「两层幂等」。
 *
 * <p>每一行代表**一次被请求过的操作**，{@link IdempotencyOutcome} 说明它实际发生了什么。
 */
@Entity
@Table(name = "operation_record")
public class OperationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "idempotency_key", length = 160, nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private OperationSource source;

    @Column(name = "operation", length = 120, nullable = false)
    private String operation;

    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 24, nullable = false)
    private IdempotencyOutcome outcome;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "response_payload", columnDefinition = "text")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 供 JPA 使用 */
    protected OperationRecordEntity() {
    }

    public OperationRecordEntity(String idempotencyKey, OperationSource source, String operation,
                                 String requestDigest, String conversationId, String turnId,
                                 Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.source = source;
        this.operation = operation;
        this.requestDigest = requestDigest;
        this.conversationId = conversationId;
        this.turnId = turnId;
        // 占位行：尚未执行完成
        this.outcome = IdempotencyOutcome.IN_PROGRESS;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OperationSource getSource() {
        return source;
    }

    public String getOperation() {
        return operation;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTurnId() {
        return turnId;
    }

    public IdempotencyOutcome getOutcome() {
        return outcome;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 回填执行结果。
     */
    public void complete(IdempotencyOutcome finalOutcome, String responsePayload, Integer durationMs) {
        this.outcome = finalOutcome;
        this.responsePayload = responsePayload;
        this.durationMs = durationMs;
    }
}
