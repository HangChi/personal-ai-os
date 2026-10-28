package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V19 的向量表表达式索引（2026-10-27）。
 *
 * <h2>为什么迁移也要有测试</h2>
 * 索引是「建了但没被用上」也不会报错的东西 —— 查询照样返回正确结果，只是慢。
 * 所以只断言「索引存在于 pg_indexes」不够：还要让<b>规划器实际采用它</b>。
 * 这里用 {@code EXPLAIN} 断言执行计划里出现索引名，把「建出来了」与「真的生效」
 * 一起锁住。
 *
 * <p>另有一个反证：删掉索引后同一条查询必须回到 Seq Scan。没有这条，
 * 上面那条断言在「表太小、规划器本来就走索引」的巧合下也可能假通过。
 *
 * <h2>验证这类迁移测试时的一个陷阱</h2>
 * 想确认「测试真的依赖 V19」而把迁移文件改名时，<b>必须同时删掉
 * {@code target/classes/db/migration/} 下的同名拷贝</b>。Maven 复制资源只增不删，
 * 文件从 src 移除后 target 里那份还在，Flyway 照样能读到 —— 于是「没有 V19」
 * 的验证会假通过，让人误以为测试没写对（我就在这里白折腾了一轮）。
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Testcontainers
class VectorStoreIndexMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final String INDEX_NAME = "idx_vector_store_document_id";

    /**
     * PgVectorStore 自动配置强依赖一个 {@code EmbeddingModel} bean ——
     * 只设 {@code spring.ai.model.embedding=none} 会启动失败（不是可选依赖）。
     * 本测试不关心语义，给个确定性词袋实现即可。
     */
    @TestConfiguration
    static class StubConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V19 建出的表达式索引存在，且对 json 列上的 ->> 查询生效")
    void documentIdExpressionIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'vector_store' AND indexname = ?",
                Integer.class, INDEX_NAME);
        assertThat(count)
                .as("V19 应建出 %s；缺失意味着删文档/替换文档/统计分块数都是全表扫描", INDEX_NAME)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("metadata 是 json 而非 jsonb —— 索引方案正是基于这一点成立")
    void metadataColumnIsJson() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns"
                        + " WHERE table_name = 'vector_store' AND column_name = 'metadata'",
                String.class);
        // 如果哪天 Spring AI 改成 jsonb，这条断言会红，提醒我们重新评估：
        // jsonb 可以直接建 GIN 索引，表达式索引不再是唯一选择
        assertThat(type).isEqualTo("json");
    }

    @Test
    @DisplayName("规划器真的采用该索引；删掉索引则回到 Seq Scan")
    void plannerUsesIndexOnlyWhenPresent() {
        // 灌 100 个文档、每个 10 块 = 1000 行，然后只查其中一个文档（选择性 1%）。
        //
        // 选择性是这条断言成立的前提：我第一版只插了一个 document_id 的 200 行，
        // 谓词命中率 100%，规划器选 Seq Scan 是**正确**行为 —— 那样测试红的是我的
        // 数据分布，不是索引。真实库里 234 行分布在 27 个文档，查一个文档约 4%，
        // 这里取 1% 留足余量。
        String documentId = "doc-target";
        jdbc.execute("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                SELECT gen_random_uuid(),
                       '分块 ' || i,
                       json_build_object(
                           'document_id', CASE WHEN i % 100 = 0 THEN 'doc-target'
                                               ELSE 'doc-' || (i % 100) END,
                           'chunk_index', i),
                       array_fill(0.0, ARRAY[1024])::vector
                FROM generate_series(1, 1000) AS i
                """);
        jdbc.execute("ANALYZE vector_store");

        List<String> withIndex = explainCountByDocumentId(documentId);
        assertThat(withIndex)
                .as("有索引时执行计划应包含索引名，实际：%s", withIndex)
                .anySatisfy(line -> assertThat(line).contains(INDEX_NAME));

        // 反证：没有这条，「表小所以走索引」也能让上面的断言通过，测试就失去意义
        jdbc.execute("DROP INDEX " + INDEX_NAME);
        try {
            jdbc.execute("ANALYZE vector_store");
            List<String> withoutIndex = explainCountByDocumentId(documentId);
            assertThat(withoutIndex)
                    .as("删掉索引后应回到全表扫描，实际：%s", withoutIndex)
                    .noneSatisfy(line -> assertThat(line).contains(INDEX_NAME));
        } finally {
            // 同一容器内的后续用例（以及本类其他断言）依赖这个索引存在
            jdbc.execute("CREATE INDEX " + INDEX_NAME
                    + " ON vector_store ((metadata->>'document_id'))");
        }
    }

    private List<String> explainCountByDocumentId(String documentId) {
        return jdbc.queryForList(
                "EXPLAIN (COSTS OFF) SELECT count(*) FROM vector_store"
                        + " WHERE metadata->>'document_id' = ?",
                String.class, documentId);
    }
}
