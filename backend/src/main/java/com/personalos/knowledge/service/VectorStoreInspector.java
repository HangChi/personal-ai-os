package com.personalos.knowledge.service;

import com.personalos.common.search.SearchPatterns;
import com.personalos.knowledge.config.KnowledgeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
     * 一次查询返回全部关键词的候选与 IDF 统计。
     *
     * <p>窗口统计先于每词截断，确保 df 是全部命中数而非候选条数。
     * 仍使用转义后的 ILIKE 子串匹配，排序按长度与 id，保持既有召回口径。
     * 每词保留独立排名和命中数，融合时仍由 IDF 抑制常见子串的噪声。
     */
    public List<KeywordMatch> searchByKeywords(List<String> tokens, int limit) {
        if (tokens.isEmpty() || limit <= 0) {
            return List.of();
        }
        String values = String.join(", ", java.util.Collections.nCopies(tokens.size(), "(?, ?)"));
        String sql = "WITH tokens(token, pattern) AS (VALUES " + values + "), ranked AS ("
                + " SELECT t.token, v.id, v.content, v.metadata,"
                + " count(*) OVER (PARTITION BY t.token) AS matched_count,"
                + " row_number() OVER (PARTITION BY t.token ORDER BY length(v.content), v.id) AS rank"
                + " FROM " + qualifiedTable() + " v CROSS JOIN tokens t"
                + " WHERE v.content ILIKE t.pattern ESCAPE '\\'"
                + ") SELECT token, id::text, content, metadata::text, matched_count,"
                + " (SELECT count(*) FROM " + qualifiedTable() + ") AS total_chunks"
                + " FROM ranked WHERE rank <= ? ORDER BY token, rank";
        List<Object> parameters = new ArrayList<>();
        for (String token : tokens) {
            parameters.add(token);
            parameters.add(SearchPatterns.likePattern(token));
        }
        parameters.add(limit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new KeywordMatch(
                rs.getString("token"),
                new KeywordChunk(rs.getString("id"), rs.getString("content"), rs.getString("metadata")),
                rs.getLong("matched_count"), rs.getLong("total_chunks")), parameters.toArray());
    }

    public record KeywordMatch(String token, KeywordChunk chunk, long matchedCount, long totalChunks) {
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
