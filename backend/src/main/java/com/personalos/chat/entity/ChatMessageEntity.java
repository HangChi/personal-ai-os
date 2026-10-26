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
 * 聊天消息。
 *
 * <p>刻意**不**把会话建模成 {@code @ManyToOne} 关联：本模块只需要会话标识这一列，
 * 引入关联会带来懒加载与 N+1 的风险，而收益为零。
 */
@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 20, nullable = false)
    private MessageRole messageType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** 附件元数据 JSON 数组（V14）；null = 无附件。文件本体在磁盘上 */
    @Column(name = "attachments", columnDefinition = "text")
    private String attachments;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 供 JPA 使用 */
    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String conversationId, MessageRole messageType, String content,
                             Integer sequenceNo, Instant createdAt) {
        this.conversationId = conversationId;
        this.messageType = messageType;
        this.content = content;
        this.sequenceNo = sequenceNo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public MessageRole getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
