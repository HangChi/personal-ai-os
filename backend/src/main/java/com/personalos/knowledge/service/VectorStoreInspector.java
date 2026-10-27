package com.personalos.knowledge.service;

import com.personalos.common.search.SearchPatterns;
import com.personalos.knowledge.config.KnowledgeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量表的**唯一** SQL 入口。
 *
 * <p>把「查向量表」的语句集中在这里，而不是散落在各 Service 里，原因有两个：
 * <ol>
 *   <li>表名与 schema 直接来自 pgvector 的配置项，避免出现「配置改了一处、SQL 还指着旧表名」；</li>
 *   <li>列名（{@code embedding} / {@code metadata}）是框架约定，将来升级 Spring AI 时只需改这里。</li>
 * </ol>
 */
@Component
public class VectorStoreInspector {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreInspector.class);

    /** 表名/schema 会拼进 SQL，因此必须限制字符集（虽然来自配置而非用户输入，仍按不可信处理） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProperties properties;
    private final String tableName;
    private final String schemaName;

    public VectorStoreInspector(JdbcTemplate jdbcTemplate,
                                KnowledgeProperties properties,
                                @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
                                @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.tableName = requireSafeIdentifier(tableName, "表名");
        this.schemaName = requireSafeIdentifier(schemaName, "schema 名");
    }

    public String tableName() {
        return tableName;
    }

    public String schemaName() {
        return schemaName;
    }

    /**
     * 读取 {@code embedding} 列的真实类型（形如 {@code vector(1024)}）。
     *
     * <p>用 {@code pg_attribute} + {@code format_type} 而不是 pgvector 的扩展函数：
     * 前者是 PostgreSQL 核心特性，不随扩展版本变化。
     *
     * @return 列类型；若表或列不存在则返回空
     */
    public Optional<String> embeddingColumnType() {
        String sql = "SELECT format_type(a.atttypid, a.atttypmod) "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname = ? AND n.nspname = ? AND a.attname = 'embedding' AND a.attnum > 0";
        return jdbcTemplate.query(sql, rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
                tableName, schemaName);
    }

    /**
     * 统计某个文档的向量条数。
     *
     * <p>删除时用它给出「实际清理了多少」的可核对数字（SC-003 断言也用它）。
     */
    public int countByDocumentId(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + qualifiedTable() + " WHERE metadata->>'document_id' = ?",
                Integer.class, documentId.toString());
        return count == null ? 0 : count;
    }

    /**
     * 分块**内容**命中关键词的文档 id（去重），供全局搜索使用（阶段 17）。
     *
     * <p>命中正文时返回的是**文档**而不是单个分块：分块量级比元数据大 1~2 个数量级，
     * 直接作为结果会让一次搜索返回几十条同源片段，分组就失去意义。
     *
     * <p>{@code ESCAPE '\'} 是必需的：关键词里的 {@code %} / {@code _} 必须按字面匹配，
     * 否则搜 {@code 100%} 会命中「100 之后任意字符」。
     *
     * <p>解析不出 UUID 的行直接跳过：宁可少一条，也不让整个搜索因为一条脏数据失败。
     */
    public List<UUID> findDocumentIdsByContent(String keyword) {
        String sql = "SELECT DISTINCT metadata->>'document_id' FROM " + qualifiedTable()
                + " WHERE content ILIKE ? ESCAPE '\\'";
        List<UUID> result = new ArrayList<>();
        for (String raw : jdbcTemplate.queryForList(sql, String.class, SearchPatterns.likePattern(keyword))) {
            if (raw == null) {
                continue;
            }
            try {
                result.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                log.debug("跳过无法解析的 document_id：{}", raw);
            }
        }
        return result;
    }

    /**
     * 关键词通道：分块内容命中任一 token 的片段（2026-10-26，混合检索用）。
     *
     * <h2>为什么是 ILIKE 子串匹配，而不是 tsvector 全文索引</h2>
     * <ul>
     *   <li>PostgreSQL 的 {@code simple} 配置只按空格/标点切词，**中文整句会变成一个 token**，
     *       中文查询在 tsvector 上直接失效；{@code zhparser} 需要额外扩展与词典。</li>
     *   <li>要救的是精确标识符（{@code 3PC} / {@code ip_hash} / {@code unknown}），
     *       子串匹配对这类 token 是最准的。</li>
     *   <li>语料 233 个分块，全表 ILIKE 扫描是毫秒级 —— 加 GIN/pg_trgm 索引带来的
     *       迁移与扩展依赖，收益不足以抵消复杂度。</li>
     * </ul>
     *
     * <h2>为什么是子串匹配，而不是词首正则</h2>
     * 子串匹配确实会捞进「看起来像但无关」的片段：233 个片段里 {@code ILIKE '%io%'}
     * 命中 118 个（51%），因为 {@code version}、{@code configuration} 里都含 {@code io}。
     *
     * <p>但试过改用词首正则（{@code ~* '\mio'}，只命中 7 个）后，**指标反而更差**
     * （R@1 85.3% → 82.9%）：词首锚定把召回也一起砍掉了，正确答案的片段常拿不到关键词加分。
     *
     * <p>正确做法不是收紧匹配，而是**让噪声不占权重** —— 见 {@link #countBySubstring}
     * 与 IDF 加权：{@code io} 这种命中 51% 片段的 token，IDF 接近 0，贡献可忽略。
     * 宽召回 + IDF 抑制，比窄召回更稳。
     *
     * <h2>为什么一次只查一个 token</h2>
     * 每个 token 单独检索、在融合时按 IDF 加权，而不是把多个 token 拼成一条 OR 查询。
     * 拼在一起会丢掉「哪个 token 命中了」的信息，而 {@code unknown}（罕见，df=8）
     * 与 {@code ip}（常见，df=13）的可信度本就不同 —— 分开查才能区别对待。
     *
     * @param token 单个关键词 token
     * @param limit 返回条数上限
     * @return 命中片段，按片段长度升序
     */
    public List<KeywordChunk> searchByKeyword(String token, int limit) {
        if (token == null || token.isBlank() || limit <= 0) {
            return List.of();
        }
        String sql = "SELECT id::text, content, metadata::text"
                + " FROM " + qualifiedTable()
                + " WHERE content ILIKE ? ESCAPE '\\'"
                // 子串匹配没有相关性分数，用片段长度兜底：同等条件下短片段信息密度更高。
                // 但 length 大量并列（同一篇文档的分块长度相近），此时返回哪几行由
                // PostgreSQL 自行决定 —— 评测因此不可复现：同一份语料两次跑出不同 R@1。
                // 补 id 兜底保证确定性；它没有语义含义，纯粹把「并列」变成一个全序。
                + " ORDER BY length(content) ASC, id ASC"
                + " LIMIT ?";

        List<KeywordChunk> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                sql, SearchPatterns.likePattern(token), limit)) {
            String id = asText(row.get("id"));
            if (id == null) {
                continue;
            }
            result.add(new KeywordChunk(id, asText(row.get("content")), asText(row.get("metadata"))));
        }
        return result;
    }

    /**
     * 该 token 命中的片段数（df） —— IDF 权重的依据。
     *
     * <p><b>统计口径必须与 {@link #searchByKeyword} 完全一致</b>（都用子串 ILIKE）。
     * 曾试过「检索用子串、df 用词首」，结果 IDF 严重低估了 {@code io} 的普遍程度
     * （词首 7 个 vs 子串 118 个），噪声没被压住，指标反而变差。
     */
    public long countBySubstring(String token) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + qualifiedTable() + " WHERE content ILIKE ? ESCAPE '\\'",
                Long.class, SearchPatterns.likePattern(token));
        return count == null ? 0L : count;
    }

    /** 片段总数 —— IDF 的分母 */
    public long countAllChunks() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + qualifiedTable(), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 构造「词首匹配」的 PostgreSQL 正则：{@code \m} + 转义后的 token。
     *
     * <p>token 里可能有正则元字符（{@code C++}、{@code Object.defineProperty}、{@code C#}），
     * 必须转义，否则 {@code .} 会匹配任意字符、{@code +} 会被当成量词。
     */
    static String wordStartPattern(String token) {
        String escaped = token.replaceAll("([\\\\^$.|?*+()\\[\\]{}])", "\\\\$1");
        return "\\m" + escaped;
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 关键词通道命中的一个分块。
     *
     * @param id       分块 id（对应 vector_store.id，也等于 Spring AI Document 的 id）
     * @param content  片段原文
     * @param metadata JSON 文本形式的元数据
     */
    public record KeywordChunk(String id, String content, String metadata) {
    }

    /**
     * 取某个文档的全部分块文本，按分块序号升序（导出用，阶段 12）。
     *
     * <p><b>为什么要专门取它</b>：{@code knowledge_document} 只存元数据，
     * 原文不落库（V4 迁移的设计），分块文本是知识库内容**唯一**的可导出形态。
     *
     * <p>{@code chunk_index} 在 {@code metadata} 里是 JSON 文本，直接 {@code ::int} 转换会在
     * 遇到脏数据时整条查询报错；所以先用正则筛出纯数字再转，非数字的排到最后，
     * 宁可顺序不完美也不要导不出来。
     */
    public List<String> chunksByDocumentId(UUID documentId) {
        String sql = "SELECT content FROM " + qualifiedTable()
                + " WHERE metadata->>'document_id' = ?"
                + " ORDER BY CASE WHEN metadata->>'chunk_index' ~ '^[0-9]+$'"
                + "               THEN (metadata->>'chunk_index')::int"
                + "               ELSE 2147483647 END";
        return jdbcTemplate.queryForList(sql, String.class, documentId.toString());
    }

    /**
     * 统计整张向量表的行数（测试与排错用）。
     */
    public int countAll() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + qualifiedTable(), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 逐行校验所有向量的维度，返回不等于期望维度的行数。
     *
     * <p>刻意**逐行**统计而不是抽查：维度不一致往往只出现在部分行上
     * （例如先按旧维度写入过数据），抽查会漏掉。
     */
    public int countWithUnexpectedDimension() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + qualifiedTable() + " WHERE vector_dims(embedding) <> ?",
                Integer.class, properties.embeddingDimensions());
        return count == null ? 0 : count;
    }

    private String qualifiedTable() {
        return schemaName + "." + tableName;
    }

    private static String requireSafeIdentifier(String value, String what) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException(what + " 非法：" + value);
        }
        return value;
    }
}
