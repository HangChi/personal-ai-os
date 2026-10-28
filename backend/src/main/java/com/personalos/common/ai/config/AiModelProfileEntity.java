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
 * 一套大模型 API 配置（V21，多行表）。
 *
 * <h2>与 V20 单行表的区别</h2>
 * V20 的 {@link AiModelConfigEntity} 用 {@code CHECK (id = 1)} 把表锁成一行，
 * 表达「只有一个生效配置」。但真实使用里用户会同时持有 DashScope、官方 OpenAI、
 * 本地 Ollama 与几家中转站，「换一套」的代价是全表重填 —— 这是本表存在的理由。
 *
 * <p>约束从「只有一行」变成「<b>至多一行为 {@code active}</b>」：多行共存，
 * 但同一时刻只有一行生效。这条不变量由 V21 的**部分唯一索引**
 * {@code uq_ai_model_profile_single_active} 在数据库层强制。
 *
 * <h2>为什么所有配置字段都可以为 null</h2>
 * 语义与 V20 一致：{@code null} =「这一项不动，回落到环境变量 / 内置默认」。
 * 切换配置时只覆盖想改的几项是很常见的用法（比如两套配置共用同一个端点、
 * 只换模型名），把未填项当成空串会把这类配置打成不可用。
 *
 * <h2>激活的顺序陷阱（改之前必读）</h2>
 * 部分唯一索引只允许一行 {@code active = TRUE}。切换时若 JPA 先 flush
 * 「把新的置 TRUE」再 flush「把旧的置 FALSE」，中间态就有两行 TRUE，
 * 数据库会直接抛 {@code duplicate key value violates unique constraint}。
 * 解法不在实体里，而在 {@code ModelProfileService#activate}：
 * 先 {@code deactivateAll()} 并**显式 flush**，再置新的为 TRUE。
 * 那是本表唯一一个「顺序会决定成败」的地方。
 */
@Entity
@Table(name = "ai_model_profile")
public class AiModelProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    /** 用户起的展示名，如「DashScope 主力」 */
    @Column(name = "name", length = 60, nullable = false)
    private String name;

    /**
     * 供应商预设标识（前端的 {@code PRESET_ID}），如 {@code dashscope} / {@code openai}。
     *
     * <p>{@code null} 表示自定义配置。不加外键：预设库在代码里，不是数据库表 ——
     * 增删预设不该要求一次迁移。
     */
    @Column(name = "preset_id", length = 60)
    private String presetId;

    /** OpenAI 兼容端点根地址；null 表示沿用环境变量 */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** API 密钥明文；null 表示沿用环境变量。接口层必须掩码后再返回 */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "chat_model", length = 120)
    private String chatModel;

    /**
     * 采样温度 0~2；null 表示沿用服务端默认。
     *
     * <p>{@code Double} 而非 {@code double}：基本类型默认 0.0，与「没设过」
     * 无法区分 —— 而 0.0 是合法极端值（完全确定性输出）。
     */
    @Column(name = "temperature")
    private Double temperature;

    /** 嵌入模型名；改动只在下次启动生效 */
    @Column(name = "embedding_model", length = 120)
    private String embeddingModel;

    /** 是否当前生效。全表至多一行为 true（部分唯一索引强制） */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** 手动排序位；相同值时按 createdAt 兜底，保证列表顺序稳定 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 保存后自增；初值 0，首次保存后为 1 */
    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiModelProfileEntity() {
        // JPA 要求
    }

    /**
     * 新建一套配置。
     *
     * @param name      展示名（调用方负责保证非空）
     * @param presetId  预设标识；null 表示自定义
     * @param sortOrder 排序位
     * @param now       当前时间
     */
    public AiModelProfileEntity(String name, String presetId, int sortOrder, Instant now) {
        this.name = name;
        this.presetId = presetId;
        this.sortOrder = sortOrder;
        this.revision = 0;
        this.active = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 整体覆盖可写的配置字段并推进 revision。
     *
     * <p>整体覆盖而不是逐字段 setter，理由与 V20 相同：设置页提交的是**整份表单**，
     * 用户把某一项清空就是要「恢复该项的环境变量取值」，逐字段「只改有值的」
     * 会让清空操作静默失效。
     *
     * <p>{@code revision} 初值 0：这样首次保存得到的正是 1。
     *
     * @param now 当前时间
     */
    public void overwrite(String name, String presetId, String baseUrl, String apiKey,
                          String chatModel, Double temperature, String embeddingModel, Instant now) {
        this.name = name;
        this.presetId = presetId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
        this.revision = this.revision + 1;
        this.updatedAt = now;
    }

    /**
     * 只改名。
     *
     * <p>单独一个方法而不是走 {@link #overwrite}：改名不该推进 revision
     * （配置内容没变），也不该要求调用方把其余字段原样传一遍 ——
     * 那样调用方一旦漏传某一项，改名就会顺手把配置清空。
     */
    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    /** 标记为生效。调用方必须先把其他行取消激活并 flush，见类注释的「顺序陷阱」 */
    public void activate(Instant now) {
        this.active = true;
        this.updatedAt = now;
    }

    /** 取消生效。注意：不允许把所有行都取消（服务层会拒绝删除/停用最后一行） */
    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    /** 调整排序位；不推进 revision，也不改 updatedAt（纯展示顺序） */
    public void reorder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPresetId() {
        return presetId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public Double getTemperature() {
        return temperature;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
