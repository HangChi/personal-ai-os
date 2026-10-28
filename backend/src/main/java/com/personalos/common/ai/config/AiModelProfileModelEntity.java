package com.personalos.common.ai.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一套配置下的一个模型名（V23）。
 *
 * <h2>为什么模型名要单独一张表</h2>
 * 一个端点通常提供几十上百个模型 —— 实测 DashScope 一次探测返回 183 个。
 * 把它们塞进 {@code ai_model_profile} 的逗号分隔字符串会让「按模型查配置」
 * 「去重」「排序」全部退化成字符串处理；而一张子表让这些都变成 SQL。
 *
 * <h2>为什么用自增 id 而不是 (profile_id, kind, model_name) 联合主键</h2>
 * 联合主键在 JPA 里需要一个 {@code @IdClass}/{@code @EmbeddedId}，
 * 而这个实体没有任何按主键单条读写的场景（永远整组替换）。自增 id 让
 * 「删掉再插一批」这条唯一的写路径保持简单。
 *
 * <h2>没有 {@code is_default} 字段</h2>
 * 「这套配置默认用哪个对话模型」由 {@code ai_model_profile.chat_model} 表达。
 * 两处存同一个事实，迟早会不一致，而不一致时无从判断谁对。
 */
@Entity
@Table(name = "ai_model_profile_model")
public class AiModelProfileModelEntity {

    /** 模型用途 */
    public enum Kind {
        /** 对话类（含视觉等聊天模型） */
        CHAT,
        /** 嵌入类。换它会改变向量维度，已有向量需重建 */
        EMBEDDING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 所属配置。用裸 UUID 列而不是 {@code @ManyToOne}：清单永远随配置整组读写，不需要对象图 */
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "kind", length = 20, nullable = false)
    private String kind;

    @Column(name = "model_name", length = 120, nullable = false)
    private String modelName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiModelProfileModelEntity() {
        // JPA 要求
    }

    /**
     * 新建一条清单项。
     *
     * @param profileId 所属配置
     * @param kind      用途
     * @param modelName 模型名（调用方负责去空白与非空）
     * @param sortOrder 清单内顺序
     * @param now       当前时间
     */
    public AiModelProfileModelEntity(UUID profileId, Kind kind, String modelName,
                                     int sortOrder, Instant now) {
        this.profileId = profileId;
        this.kind = kind.name();
        this.modelName = modelName;
        this.sortOrder = sortOrder;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public String getKind() {
        return kind;
    }

    /** 解析后的用途；脏数据（不可能出现，库层有 CHECK）回落到 CHAT */
    public Kind kindEnum() {
        return Kind.EMBEDDING.name().equals(kind) ? Kind.EMBEDDING : Kind.CHAT;
    }

    public String getModelName() {
        return modelName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
