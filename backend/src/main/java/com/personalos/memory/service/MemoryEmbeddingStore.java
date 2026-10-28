package com.personalos.memory.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 记忆向量的读写（pgvector）。
 *
 * <h2>为什么不映射到实体</h2>
 * Hibernate 没有 {@code vector} 类型，项目也没有引入 pgvector 的 Hibernate 方言
 * （知识库走的是 Spring AI 的 VectorStore，不碰实体层）。所以这里用一段集中、
 * 可测的 SQL 处理向量列，实体只保留 {@code embeddingModel} / {@code embeddedAt}
 * 两个「元信息」字段。
 *
 * <h2>为什么相似度查询必须带模型名</h2>
 * 不同 embedding 模型产出的向量处在不同空间，直接算余弦会得到一个「看着合理、
 * 实际无意义」的数 —— 它不会报错，只会让错误条目被静默判成重复。
 * 因此每条 SQL 都把 {@code embedding_model = ?} 作为硬条件，查不到就当作「无重复」。
 */
@Component
public class MemoryEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingStore.class);

    /**
     * 近邻查询：返回「同模型、未被取代、最相似」的一条。
     *
     * <p>{@code <=>} 是 pgvector 的余弦**距离**（1 - 相似度），所以相似度要反算。
     * 排序与阈值都作用在同一个算子与同一个 opclass（{@code vector_cosine_ops}）上 ——
     * 换成 {@code <->} 而不换索引会静默失去索引加速。
     */
    private static final String FIND_SIMILAR_SQL = """
            SELECT id, 1 - (embedding <=> CAST(? AS vector)) AS similarity
            FROM memory
            WHERE superseded_by IS NULL
              AND embedding IS NOT NULL
              AND embedding_model = ?
              AND id <> CAST(? AS uuid)
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public MemoryEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入向量。
     *
     * <p>维度不符时**放弃写入并返回 false**，不抛异常：维度由模型决定（可能是用户
     * 在设置页换过的模型），失败应降级成「这条暂时没有向量」，而不是让保存记忆报错。
     *
     * <p>模型名与向量必须在同一条 UPDATE 里写：分两次写会出现「向量已是新模型的、
     * 模型名还是旧的」的中间态，而比较逻辑正是按模型名分组的 —— 那个中间态会让
     * 两条不同空间的向量被当成可比。
     *
     * @param id     记忆标识
     * @param vector 向量
     * @param model  产出该向量的模型名
     * @return 是否写入成功
     */
    public boolean save(UUID id, float[] vector, String model) {
        if (id == null || vector == null || vector.length == 0) {
            return false;
        }
        int written;
        try {
            written = jdbcTemplate.update(
                    "UPDATE memory SET embedding = CAST(? AS vector), embedding_model = ?, "
                            + "embedded_at = now() WHERE id = ?",
                    toLiteral(vector), model, id);
        } catch (DataAccessException ex) {
            // 维度与列定义（vector(1024)）不符时 PG 直接报错。这不是致命错误：
            // 用户换了不同维度的 embedding 模型，记忆本身仍然可用，只是暂时没有向量。
            log.warn("写记忆向量失败（维度或类型不符），该条将暂不参与相关度排序：id={} 维度={} 原因={}",
                    id, vector.length, ex.getMostSpecificCause().getMessage());
            return false;
        }
        if (written == 0) {
            log.warn("写记忆向量时未命中任何行 id={}（可能已被删除）", id);
            return false;
        }
        return true;
    }

    /**
     * 找出一条与给定向量「同模型且足够相似」的记忆。
     *
     * @param vector    查询向量
     * @param model     产出该向量的模型名
     * @param threshold 相似度下界
     * @param excludeId 排除自身（更新场景）
     * @return 命中的条目与相似度；无命中返回空
     */
    public Optional<SimilarMatch> findSimilar(float[] vector, String model, double threshold, UUID excludeId) {
        if (vector == null || vector.length == 0 || model == null || model.isBlank()) {
            return Optional.empty();
        }
        String literal = toLiteral(vector);
        List<SimilarMatch> matches = jdbcTemplate.query(FIND_SIMILAR_SQL,
                (rs, rowNum) -> new SimilarMatch(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("similarity")),
                literal, model, excludeId == null ? ZERO_UUID : excludeId.toString(), literal);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        SimilarMatch match = matches.get(0);
        return match.similarity() >= threshold ? Optional.of(match) : Optional.empty();
    }

    /**
     * 待回填的条目：还没有向量、且仍参与注入。
     *
     * @param limit 单批上限
     * @return 按更新时间倒序的待处理条目
     */
    public List<PendingEmbedding> findPending(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, content FROM memory
                        WHERE embedded_at IS NULL AND superseded_by IS NULL
                        ORDER BY updated_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new PendingEmbedding(rs.getObject("id", UUID.class), rs.getString("content")),
                limit);
    }

    /**
     * 一次性算出「同模型下每条记忆与查询的相似度」。
     *
     * <h2>为什么不在内存里逐条算</h2>
     * 逐条算需要先把 120 条的向量全读出来（每条 1024 个 float，约 480 KB），
     * 再在 JVM 里做点积 —— 数据搬运量远大于让 pgvector 排一次序。
     * 这里一次 SQL 拿到 {@code id → similarity}，排序在数据库侧完成。
     *
     * <p>没有向量的条目**不会**出现在返回的映射里。调用方据此把它们当作
     * 「相关度未知」处理，而不是「相关度为 0」—— 两者对排序的含义不同：
     * 未知应该靠新近度兜底，0 会把它们永久压到底部。
     *
     * @param vector 查询向量
     * @param model  模型名（不同模型的向量不可比）
     * @param limit  最多返回多少条
     * @return {@code id → 相似度}（0~1）
     */
    public Map<UUID, Double> similarityTo(float[] vector, String model, int limit) {
        if (vector == null || vector.length == 0 || model == null || model.isBlank()) {
            return Map.of();
        }
        String literal = toLiteral(vector);
        Map<UUID, Double> result = new HashMap<>();
        jdbcTemplate.query("""
                        SELECT id, 1 - (embedding <=> CAST(? AS vector)) AS similarity
                        FROM memory
                        WHERE superseded_by IS NULL AND embedding IS NOT NULL AND embedding_model = ?
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """,
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getDouble("similarity"));
                },
                literal, model, literal, limit);
        return result;
    }

    /** 清空某条记忆的向量（内容变更或模型切换时调用） */
    public void clear(UUID id) {
        jdbcTemplate.update("UPDATE memory SET embedding = NULL, embedded_at = NULL WHERE id = ?", id);
    }

    /**
     * 向量转 pgvector 字面量（{@code [0.1,0.2,...]}）。
     *
     * <p>不用 {@code Arrays.toString}：它给出的 {@code [0.1, 0.2]} 带空格，
     * pgvector 能容忍却会增加解析开销；这里直接拼紧凑形式。
     */
    static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** 近重复命中：哪条、多像 */
    public record SimilarMatch(UUID id, double similarity) {
    }

    /** 待回填条目 */
    public record PendingEmbedding(UUID id, String content) {
    }

    /** 用于「不排除任何条目」的占位标识（UUID 全零不会与任何真实记忆相同） */
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";
}
