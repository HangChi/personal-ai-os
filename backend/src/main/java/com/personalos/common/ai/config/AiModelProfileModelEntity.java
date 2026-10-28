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
 * <h2>三类用途（V24 增加 AUDIO）</h2>
 * CHAT / EMBEDDING / AUDIO 对应界面上三栏。分开的理由不只是展示：
 * 换嵌入模型会让已有向量失效、语音模型只服务转写接口、
 * 对话模型才会出现在聊天页的下拉里 —— 三者的语义与后果完全不同。
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
        EMBEDDING,
        /**
         * 语音类（V24）：语音识别（ASR）与语音合成（TTS）。
         *
         * <p>它不在聊天链路里，只被 {@code AudioTranscriptionService} 按名取用。
         * 与 CHAT 分开放的理由与 EMBEDDING 相同：界面上分栏，
         * 且「选了对话模型」与「选了语音模型」是两种完全不同的用法。
         */
        AUDIO
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

    /**
     * 该模型支持的上下文窗口（token）。NULL = 未知，回落到全局配置（V25）。
     *
     * <h3>为什么挂在模型行上而不是配置行上</h3>
     * 一套配置下可以同时有 {@code qwen-max}（32K）与 {@code qwen-long}（1M），
     * 窗口差 30 倍。挂在配置上就只能填一个值，必然对其中一个模型是错的。
     *
     * <h3>为什么允许 NULL 而不是给个默认值</h3>
     * NULL 的语义是「不知道」。此时回落到 {@code app.ai.memory.context-window-tokens}
     * —— 这是**已有数据的行为**，本次迁移不改变任何存量模型的运行表现。
     * 若填一个猜的默认值，界面上会显示一个看起来精确、实际可能错的数，
     * 用户没有理由怀疑它，反而更危险。
     */
    @Column(name = "context_window")
    private Integer contextWindow;

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
        this(profileId, kind, modelName, sortOrder, null, now);
    }

    /**
     * 新建一条清单项（带上下文窗口，V25）。
     *
     * @param profileId     所属配置
     * @param kind          用途
     * @param modelName     模型名（调用方负责去空白与非空）
     * @param sortOrder     清单内顺序
     * @param contextWindow 上下文窗口（token）；null 或非法值表示未知，
     *                      运行期回落到全局配置
     * @param now           当前时间
     */
    public AiModelProfileModelEntity(UUID profileId, Kind kind, String modelName,
                                     int sortOrder, Integer contextWindow, Instant now) {
        this.profileId = profileId;
        this.kind = kind.name();
        this.modelName = modelName;
        this.sortOrder = sortOrder;
        setContextWindow(contextWindow);
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

    /**
     * 解析后的用途；脏数据（不可能出现，库层有 CHECK）回落到 CHAT。
     *
     * <p>用逐值比较而不是 {@code Kind.valueOf}：库里若有 V24 之后新增但本代码
     * 尚未认识的取值，{@code valueOf} 会抛 {@code IllegalArgumentException}
     * 让整张列表 500，而回落到 CHAT 至少能把它显示出来。
     */
    public Kind kindEnum() {
        for (Kind candidate : Kind.values()) {
            if (candidate.name().equals(kind)) {
                return candidate;
            }
        }
        return Kind.CHAT;
    }

    public String getModelName() {
        return modelName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    /** 该模型的上下文窗口（token）；null 表示未知，调用方应回落到全局配置 */
    public Integer getContextWindow() {
        return contextWindow;
    }

    /**
     * 设置上下文窗口。
     *
     * <p>低于 1024 一律当「未知」处理成 null，而不是抛异常：这个值可能来自
     * 端点探测的外来数据（字段单位可能是 K 或字符数），把一个明显不合理的数
     * 落库会让仪表盘显示「/ 1」这种无法解释的状态。库层也有同样的 CHECK 兜底。
     */
    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow == null || contextWindow < 1024 ? null : contextWindow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
