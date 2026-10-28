package com.personalos.common.ai.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 大模型 API 配置（V20，单行，主键恒为 1）。
 *
 * <h2>为什么是单行实体而不是「配置项的键值对」</h2>
 * 键值对表（{@code key / value}）看起来更通用，但会让**类型与约束全部丢失**：
 * 温度只能存成字符串、范围校验只能写在代码里、拼错键名不会报错只会「读不到」。
 * 这里是六个固定字段，用列类型与 CHECK 约束表达它们的语义，改一个字段就走迁移。
 * 配置项少、变更频率低，这正是关系表最擅长的形态。
 *
 * <h2>为什么所有字段都可为 null</h2>
 * {@code null} 的含义是「用户没设过，回落到环境变量或内置默认」，与
 * 「用户显式设成了空字符串」是两件事。这个区分很重要：
 * 后端把 {@code OPENAI_CHAT_MODEL} 的默认值从 A 改成 B 时，
 * 从没在设置页碰过这一项的用户应当跟着变成 B；
 * 只有真正覆盖过的人才该保持自己的值。
 *
 * <h2>为什么 api_key 存明文</h2>
 * 见迁移脚本头部的取舍说明。简言之：本机自托管 + 库不对外 + 单用户，
 * 加密的密钥本身仍要放在 .env 里，攻击面只是从「读库」挪到「读 .env」。
 * 真正防的是截屏与日志泄露，那由接口层的掩码返回解决。
 */
@Entity
@Table(name = "ai_model_config")
public class AiModelConfigEntity {

    /** 单行表：主键固定为 1，数据库有 CHECK 约束保证 */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id = SINGLETON_ID;

    /** OpenAI 兼容端点根地址；null 表示沿用环境变量 OPENAI_BASE_URL */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** API 密钥明文；null 表示沿用环境变量 OPENAI_API_KEY */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /** 对话模型名；null 表示沿用环境变量 OPENAI_CHAT_MODEL */
    @Column(name = "chat_model", length = 120)
    private String chatModel;

    /**
     * 采样温度 0~2；null 表示沿用环境变量配置。
     *
     * <p>用 {@code Double} 而非 {@code double}：基本类型在这里会有默认值 0.0，
     * 与「没设过」无法区分 —— 而 0.0 是个合法的极端值（完全确定性输出）。
     */
    @Column(name = "temperature")
    private Double temperature;

    /**
     * 嵌入模型名；null 表示沿用环境变量 OPENAI_EMBEDDING_MODEL。
     *
     * <p><b>改动只在下次启动生效</b>：向量表维度由 DDL 固定为 1024，
     * 换一个不同维度的模型会让写入直接失败。运行期不做切换，是刻意的。
     */
    @Column(name = "embedding_model", length = 120)
    private String embeddingModel;

    /** 保存后自增，用于回读确认与审计；首次保存即为 1 */
    @Column(name = "revision", nullable = false)
    private Integer revision = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiModelConfigEntity() {
        // JPA 要求
    }

    public AiModelConfigEntity(Instant now) {
        this.updatedAt = now;
    }

    /**
     * 覆盖全部可写字段并推进 revision。
     *
     * <p>整体覆盖而不是逐字段 setter：设置页提交的是**整份表单**，
     * 用户把某一项清空就是要「恢复该字段的环境变量取值」，
     * 逐字段「只改有值的」会让清空操作静默失效。
     *
     * <p>revision 初值是 0 而不是 1：这样「首次保存」得到的正是 1，与
     * 「这是第几次保存」的字面含义一致。若初值为 1，新建后第一次 overwrite
     * 就变成 2，回读时看起来像漏掉了一次保存。
     *
     * @param now 当前时间（由调用方注入 Clock，测试可控）
     */
    public void overwrite(String baseUrl, String apiKey, String chatModel,
                          Double temperature, String embeddingModel, Instant now) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
        this.revision = this.revision == null ? 1 : this.revision + 1;
        this.updatedAt = now;
    }

    public Short getId() {
        return id;
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

    public Integer getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
