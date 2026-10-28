package com.personalos.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 长期记忆的分层与去重配置（前缀 {@code app.memory}）。
 *
 * <p>与 {@code app.ai} / {@code app.context} 分开的理由：这三组风险面不同 ——
 * 关掉记忆排序只是「排得没那么准」，关掉模型配置则整个对话不可用。
 *
 * <p><b>注意</b>：配置记录只能有**一个**构造器。加便捷重载会让 Spring Boot 的
 * 构造器绑定挑错那个，字段静默不绑定（2026-10-26 在 {@code AiProperties.Memory}
 * 上踩过，症状是「配置不生效」而不是启动报错）。
 *
 * @param pinnedMaxCount   常驻条目上限。超过时按最近更新保留，避免「全部置顶」挤爆预算
 * @param similarThreshold 近重复判定阈值（余弦相似度）。0.92 是「同一事实的改写」的经验下界
 * @param ranking          相关性排序（嵌入）
 * @param backfill         存量条目向量回填
 */
@ConfigurationProperties(prefix = "app.memory")
public record MemoryProperties(int pinnedMaxCount, double similarThreshold,
                               Ranking ranking, Backfill backfill) {

    private static final int DEFAULT_PINNED_MAX_COUNT = 10;
    private static final double DEFAULT_SIMILAR_THRESHOLD = 0.92;

    public MemoryProperties {
        if (pinnedMaxCount <= 0) {
            pinnedMaxCount = DEFAULT_PINNED_MAX_COUNT;
        }
        // 阈值必须落在 (0,1]：0 会把任何条目判成重复，1 则只认完全相同（等于没开）
        if (similarThreshold <= 0 || similarThreshold > 1) {
            similarThreshold = DEFAULT_SIMILAR_THRESHOLD;
        }
        if (ranking == null) {
            ranking = new Ranking(true, null, 0);
        }
        if (backfill == null) {
            backfill = new Backfill(true, 0);
        }
    }

    /**
     * 相关性排序。
     *
     * @param enabled       关闭后回退到「字符二元组重叠」，不再调用嵌入模型
     * @param timeout       查询向量的等待上限。**必须有**：它在每轮对话的关键路径上，
     *                      嵌入服务卡住不能把整轮问答拖死
     * @param halfLifeDays  新近度衰减半衰期（天）。0 表示不衰减，只按相关度排
     */
    public record Ranking(boolean enabled, Duration timeout, int halfLifeDays) {

        private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(400);
        private static final int DEFAULT_HALF_LIFE_DAYS = 30;

        public Ranking {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = DEFAULT_TIMEOUT;
            }
            if (halfLifeDays < 0) {
                halfLifeDays = DEFAULT_HALF_LIFE_DAYS;
            }
        }
    }

    /**
     * 存量回填。
     *
     * @param enabled   总开关。V26 之前写入的条目没有向量，不补就永远排不上相关度
     * @param batchSize 单次补多少条。刻意很小：回填是后台任务，不该与对话抢嵌入配额
     */
    public record Backfill(boolean enabled, int batchSize) {

        private static final int DEFAULT_BATCH_SIZE = 20;

        public Backfill {
            if (batchSize <= 0) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
        }
    }
}
