package com.personalos.memory.service;

import com.personalos.memory.entity.MemoryEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 记忆注入排序的算分规则（从 {@code MemoryService} 抽出，2026-10-29）。
 *
 * <h2>为什么值得单独成类</h2>
 * 这段是「哪几条记忆先被放进系统提示」的唯一判据，出错的形态是<b>静默的排序变化</b>：
 * 常量写错一个百分点、半衰期单位从「天」算成「小时」，都不会抛异常，
 * 只表现为「该常驻的没常驻」「老记忆被新记忆挤掉」。
 *
 * <p>原先它散在 Service 的私有方法里，要验证只能把整个 Service 连同
 * 仓库、嵌入服务、事务管理器一起 mock 起来 —— 成本高到实际没人写，
 * 于是这块逻辑一直只靠「读代码觉得对」。
 *
 * <h2>为什么不把权重做成配置</h2>
 * 两个权重是<b>产品口径</b>而不是部署参数：「记忆的相关性比新鲜度重要，但新鲜度不能被忽略」。
 * 做成可配会让「换个环境排序变了」这种问题极难复现。真要调就改这里的常量并补用例。
 */
public final class MemoryRelevance {

    /**
     * 相关度权重。
     *
     * <p>0.7 而不是 1.0：纯相关度排序会把半年前的说话习惯顶到最前面，
     * 而用户最近表达过的偏好显然更该生效。
     */
    public static final double RELEVANCE_WEIGHT = 0.7;

    /** 新近度权重。与 {@link #RELEVANCE_WEIGHT} 之和为 1，保证得分落在 [0,1] */
    public static final double RECENCY_WEIGHT = 0.3;

    private MemoryRelevance() {
    }

    /**
     * 单条记忆的注入得分 = 0.7 × 相关度 + 0.3 × 新近度。
     *
     * @param memory        待打分条目
     * @param similarities  「记忆 id → 余弦相似度」；缺失表示没进向量召回
     * @param queryGrams    用户输入的二元组，用于相似度缺失时的词面兜底
     * @param now           当前时刻（测试注入）
     * @param halfLifeDays  新近度半衰期；{@code <= 0} 表示不衰减
     */
    public static double score(MemoryEntity memory,
                               Map<UUID, Double> similarities,
                               Set<String> queryGrams,
                               Instant now,
                               int halfLifeDays) {
        double relevance = 0;
        // id 为 null 只在「尚未落库的对象」上出现（单测直接构造实体）。
        // 这里显式挡住：Map.of() 不可变，get(null) 会抛 NPE ——
        // 一条还没落库的记忆不该让整轮渲染崩掉。
        Double similarity = memory.getId() == null ? null : similarities.get(memory.getId());
        if (similarity != null) {
            // 余弦可能因浮点误差略超出 [0,1]，夹一下保证它不会压过满分
            relevance = Math.max(0, Math.min(1, similarity));
        } else if (!queryGrams.isEmpty()) {
            relevance = overlapRatio(queryGrams, textGrams(memory.getContent()));
        }
        return RELEVANCE_WEIGHT * relevance + RECENCY_WEIGHT * recency(memory.getUpdatedAt(), now, halfLifeDays);
    }

    /**
     * 新近度（0~1）：经过一个半衰期降到 0.5。
     *
     * @param halfLifeDays 半衰期（天）；{@code <= 0} 或时间缺失时恒为 1，
     *                     此时得分退化为「只看相关度」
     */
    public static double recency(Instant updatedAt, Instant now, int halfLifeDays) {
        if (halfLifeDays <= 0 || updatedAt == null) {
            return 1.0;
        }
        double days = Math.max(0, Duration.between(updatedAt, now).toMinutes() / (double) (60 * 24));
        return Math.pow(0.5, days / halfLifeDays);
    }

    /**
     * 重叠率 = 查询二元组中出现在记忆里的比例（0~1）。
     *
     * <p>分母是**查询**而不是记忆：问「我喜欢什么语言」时，
     * 一条短记忆只要命中关键词就该得高分，不该因为记忆本身很长而被稀释。
     */
    public static double overlapRatio(Set<String> queryGrams, Set<String> memoryGrams) {
        if (queryGrams.isEmpty()) {
            return 0;
        }
        long hit = queryGrams.stream().filter(memoryGrams::contains).count();
        return (double) hit / queryGrams.size();
    }

    /**
     * 中文/英文通用的字符二元组。
     *
     * <p>先去掉所有空白并转小写，再逐对取两字符。单字符输入得到空集合 ——
     * 一个字的查询没有可比对的二元组，此时调用方退化为时间排序，而不是「全都不相关」。
     */
    public static Set<String> textGrams(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }
}
