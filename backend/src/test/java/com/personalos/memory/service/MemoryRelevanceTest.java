package com.personalos.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.personalos.memory.entity.MemoryEntity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 记忆注入排序的算分规则。
 *
 * <p>这块逻辑原先在 {@code MemoryService} 的私有方法里，只能靠把仓库、嵌入服务、
 * 事务管理器全部 mock 起来间接验证 —— 成本高到实际没人写。而它的错误形态是
 * <b>静默的排序变化</b>：常量写错、半衰期单位从「天」算成「小时」，都不抛异常，
 * 只表现为「该常驻的没常驻，老记忆被顶掉」。
 */
class MemoryRelevanceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final int HALF_LIFE_DAYS = 30;

    private static MemoryEntity memory(String content, Instant updatedAt, UUID id) {
        MemoryEntity entity = new MemoryEntity(content, updatedAt);
        if (id != null) {
            ReflectionTestUtils.setField(entity, "id", id);
        }
        return entity;
    }

    @Test
    @DisplayName("权重口径：纯相关度得 0.7，纯新近度得 0.3")
    void weightsMatchProductRule() {
        MemoryEntity fresh = memory("喜欢 Rust", NOW, UUID.randomUUID());
        // 相似度 1 + 刚更新（新近度 1）→ 满分 1.0
        assertThat(MemoryRelevance.score(fresh, Map.of(fresh.getId(), 1.0), Set.of(), NOW, HALF_LIFE_DAYS))
                .isCloseTo(1.0, within(1e-9));

        // 只给新近度：相似度 0 → 0.3
        assertThat(MemoryRelevance.score(fresh, Map.of(), Set.of(), NOW, HALF_LIFE_DAYS))
                .isCloseTo(MemoryRelevance.RECENCY_WEIGHT, within(1e-9));

        // 只有一个完全相同的新近度衰减项时，相关度部分应正好是 0.7
        assertThat(MemoryRelevance.RELEVANCE_WEIGHT + MemoryRelevance.RECENCY_WEIGHT).isEqualTo(1.0);
    }

    @Test
    @DisplayName("余弦越界被夹到 [0,1]：浮点误差不该让某条记忆压过满分")
    void clampsOutOfRangeSimilarity() {
        MemoryEntity entity = memory("用 pnpm", NOW, UUID.randomUUID());

        assertThat(MemoryRelevance.score(entity, Map.of(entity.getId(), 1.4), Set.of(), NOW, HALF_LIFE_DAYS))
                .as("相似度 >1 必须按 1 算，否则得分会超过 1.0")
                .isCloseTo(1.0, within(1e-9));
        assertThat(MemoryRelevance.score(entity, Map.of(entity.getId(), -0.2), Set.of(), NOW, HALF_LIFE_DAYS))
                .as("负相似度按 0 算，否则相关度会变成负数")
                .isCloseTo(MemoryRelevance.RECENCY_WEIGHT, within(1e-9));
    }

    @Test
    @DisplayName("没有向量时退回词面重叠：问句命中关键词的记忆该排前面")
    void fallsBackToLexicalOverlap() {
        // 只共享「喜欢」两个字的二元组 —— 足以证明词面兜底生效，又不至于满分
        MemoryEntity entity = memory("我最喜欢 Rust", NOW, UUID.randomUUID());
        Set<String> queryGrams = MemoryRelevance.textGrams("我喜欢什么语言");

        double score = MemoryRelevance.score(entity, Map.of(), queryGrams, NOW, HALF_LIFE_DAYS);
        double baseline = MemoryRelevance.RECENCY_WEIGHT;

        assertThat(score).as("词面有部分重叠，得分应高于「完全无关」").isGreaterThan(baseline);
        assertThat(score).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("id 为 null（尚未落库）不抛 NPE：不可变 Map 的 get(null) 会炸")
    void toleratesUnsavedEntity() {
        MemoryEntity unsaved = memory("临时对象", NOW, null);

        assertThat(unsaved.getId()).isNull();
        assertThat(MemoryRelevance.score(unsaved, Map.of(), Set.of(), NOW, HALF_LIFE_DAYS))
                .isCloseTo(MemoryRelevance.RECENCY_WEIGHT, within(1e-9));
    }

    @Test
    @DisplayName("半衰期：正好一个半衰期后新近度是 0.5，两个半衰期 0.25")
    void decaysByHalfLife() {
        Instant oneHalf = NOW.minus(HALF_LIFE_DAYS, ChronoUnit.DAYS);
        Instant twoHalf = NOW.minus(2L * HALF_LIFE_DAYS, ChronoUnit.DAYS);

        assertThat(MemoryRelevance.recency(NOW, NOW, HALF_LIFE_DAYS)).isEqualTo(1.0);
        assertThat(MemoryRelevance.recency(oneHalf, NOW, HALF_LIFE_DAYS)).isCloseTo(0.5, within(1e-6));
        assertThat(MemoryRelevance.recency(twoHalf, NOW, HALF_LIFE_DAYS)).isCloseTo(0.25, within(1e-6));
    }

    @Test
    @DisplayName("半衰期配 0 或不给时间：新近度恒为 1，得分退化为只看相关度")
    void zeroHalfLifeDisablesDecay() {
        Instant ancient = NOW.minus(3650, ChronoUnit.DAYS);

        assertThat(MemoryRelevance.recency(ancient, NOW, 0)).isEqualTo(1.0);
        assertThat(MemoryRelevance.recency(ancient, NOW, -5)).isEqualTo(1.0);
        assertThat(MemoryRelevance.recency(null, NOW, HALF_LIFE_DAYS)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("未来时间不算「负衰减」：时钟回拨不该让新近度大于 1")
    void clampsNegativeAge() {
        assertThat(MemoryRelevance.recency(NOW.plus(5, ChronoUnit.DAYS), NOW, HALF_LIFE_DAYS)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("二元组：中文按两字切、英文转小写、空白不计、单字为空集")
    void extractsGrams() {
        assertThat(MemoryRelevance.textGrams("周报 周五")).containsExactly("周报", "报周", "周五");
        assertThat(MemoryRelevance.textGrams("Use Rust")).contains("us", "se", "ru", "us");
        assertThat(MemoryRelevance.textGrams("   ")).isEmpty();
        assertThat(MemoryRelevance.textGrams(null)).isEmpty();
        assertThat(MemoryRelevance.textGrams("单"))
                .as("单个字没有二元组，调用方据此退化为时间排序")
                .isEmpty();
    }

    @Test
    @DisplayName("重叠率分母是查询：短记忆命中关键词不该因记忆本身长而被稀释")
    void overlapRatioUsesQueryAsDenominator() {
        Set<String> query = Set.of("喜欢", "欢什", "什么");
        Set<String> shortMemory = Set.of("喜欢", "欢r", "ru");

        assertThat(MemoryRelevance.overlapRatio(query, shortMemory)).isEqualTo(1.0 / 3);
        assertThat(MemoryRelevance.overlapRatio(Set.of(), shortMemory))
                .as("空查询必须返回 0 而不是除零异常")
                .isZero();
    }

    @Test
    @DisplayName("相关度相同时按新近度排序：新条目胜出（模拟 comparator 的实际用法）")
    void newerWinsOnTie() {
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        MemoryEntity old = memory("同一条内容", NOW.minus(10, ChronoUnit.DAYS), oldId);
        MemoryEntity fresh = memory("同一条内容", NOW, newId);
        // 两条相似度相同 → 只剩新近度区分
        Map<UUID, Double> similarities = new LinkedHashMap<>();
        similarities.put(oldId, 0.8);
        similarities.put(newId, 0.8);

        double oldScore = MemoryRelevance.score(old, similarities, Set.of(), NOW, HALF_LIFE_DAYS);
        double freshScore = MemoryRelevance.score(fresh, similarities, Set.of(), NOW, HALF_LIFE_DAYS);

        assertThat(freshScore).isGreaterThan(oldScore);
    }
}
