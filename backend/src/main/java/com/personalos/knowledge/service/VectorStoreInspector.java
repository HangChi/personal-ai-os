package com.personalos.knowledge.service;

import com.personalos.knowledge.config.KnowledgeProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
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
