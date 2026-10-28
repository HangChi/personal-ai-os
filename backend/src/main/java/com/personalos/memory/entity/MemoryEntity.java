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

    /**
     * 常驻标记（V26）：无条件注入、优先占预算。
     *
     * <p>这是 R-03 留下的口子 —— 记忆是约束性事实，「相关才注入」会漏掉必须生效的约束。
     * 与其对全部条目做相似度召回，不如让用户明确指定哪几条必须常在。
     */
    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    /**
     * 被哪条更新的记忆取代（V26）。
     *
     * <p>非空即不再参与注入，但**不物理删除**：用户需要看到「这条被哪条取代了」，
     * 否则自动去重就成了静默删数据。自引用外键在库层是 {@code ON DELETE SET NULL}，
     * 所以这只是一个普通列而不是 {@code @ManyToOne}（不需要级联语义）。
     */
    @Column(name = "superseded_by")
    private UUID supersededBy;

    /**
     * 向量产出的模型名（V26）。
     *
     * <p>不同模型（甚至同系列不同版本）的向量不可比。切换 embedding 模型后，
     * 旧向量必须靠它被识别出来并跳过比较 —— 否则余弦相似度会在两个无关空间里
     * 算出一个看着合理、实际无意义的数。
     *
     * <p>{@code embedding} 本体刻意**不映射**：Hibernate 不认 {@code vector} 类型，
     * 读写统一走 {@code MemoryEmbeddingStore} 的 SQL（项目里 pgvector 只由知识库
     * 的 VectorStore 使用，实体层不引入 pgvector 方言）。
     */
    @Column(name = "embedding_model", length = 120)
    private String embeddingModel;

    /** 向量产出时间；null 表示还没算过（回填任务的筛选条件） */
    @Column(name = "embedded_at")
    private Instant embeddedAt;

    /** 累计被注入到对话上下文的次数（V26） */
    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    /** 最近一次被注入的时间；null 表示从未被注入 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

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

    public boolean isPinned() {
        return pinned;
    }

    /** 置顶/取消置顶。不改 {@code updated_at}：这是展示与注入策略，不是内容变更 */
    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public UUID getSupersededBy() {
        return supersededBy;
    }

    /** 标记被更新的条目取代；传 null 表示撤销取代（用户手动取消时用） */
    public void setSupersededBy(UUID supersededBy) {
        this.supersededBy = supersededBy;
    }

    /** 是否仍参与注入 */
    public boolean isActive() {
        return supersededBy == null;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Instant getEmbeddedAt() {
        return embeddedAt;
    }

    /**
     * 记录向量已产出。
     *
     * <p>只记「哪个模型、什么时候」，向量本体由 {@code MemoryEmbeddingStore} 写入
     * —— 同一事务里更新两处会让「实体保存」与「SQL 写向量」的时机不可控。
     */
    public void markEmbedded(String embeddingModel, Instant now) {
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? null : embeddingModel.strip();
        this.embeddedAt = now;
    }

    /** 向量失效（模型切换、内容变更）：清掉标记，让回填与排序都跳过它 */
    public void invalidateEmbedding() {
        this.embeddingModel = null;
        this.embeddedAt = null;
    }

    public int getHitCount() {
        return hitCount;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /** 记录一次被注入。调用方负责批量落库，不在渲染路径上逐条 save */
    public void recordUsage(Instant now) {
        this.hitCount = this.hitCount + 1;
        this.lastUsedAt = now;
    }

    /** 内容哈希（与 V17 回填 SQL 的 encode(digest(content,'sha256'),'hex') 口径一致） */
    private static String hash(String content) {
        return com.personalos.common.json.JsonCodec.sha256Hex(content);
    }
}
