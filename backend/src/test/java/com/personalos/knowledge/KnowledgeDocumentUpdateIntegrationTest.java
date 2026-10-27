package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
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
 * 文档更新的核心约束：**id 不变**、**向量整组替换**。
 *
 * <p>这两条都是「不做就会静默出错」的类型：
 * id 变了 → 历史回答里的引用全部指向不存在的文档；
 * 向量没替换干净 → 检索会返回文档里已经不存在的段落，回答看起来有出处、点开却对不上。
 *
 * <p>与知识库其他集成测试一样，用确定性嵌入模型（词袋），只验链路不验语义。
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.knowledge.min-chunk-size-chars=10",
        "app.knowledge.similarity-threshold=0.0"
})
@Testcontainers
class KnowledgeDocumentUpdateIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }

    private static final String ORIGINAL = """
            # 旧版排课说明

            周三下午两点在 301 教室上数学课，教师是王老师。
            """;

    private static final String REPLACED = """
            # 新版排课说明

            周四上午十点在 502 教室上物理课，教师是李老师。
            这学期新增了实验环节，每周一次。
            """;

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private KnowledgeRetrievalService retrievalService;

    @Autowired
    private VectorStoreInspector inspector;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        // 护栏：确认连的是 Testcontainers 临时库，避免清到开发者的真实数据
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (!"test".equals(database)) {
            throw new IllegalStateException("拒绝在非测试库上清理（当前库：" + database + "）");
        }
        jdbc.execute("DELETE FROM vector_store");
        jdbc.execute("DELETE FROM knowledge_document");
    }

    @Test
    @DisplayName("更新后 id 不变、元数据按新文件重算、向量整组替换")
    void updatesInPlace() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));
        int oldChunks = original.chunkCount();
        assertThat(inspector.countByDocumentId(original.id())).isEqualTo(oldChunks);

        KnowledgeDocumentDto updated = documentService.update(
                original.id(), "schedule-v2.md", REPLACED.getBytes(StandardCharsets.UTF_8));

        assertThat(updated.id()).as("id 必须不变，否则引用快照全部断链").isEqualTo(original.id());
        assertThat(updated.filename()).isEqualTo("schedule-v2.md");
        assertThat(updated.title()).as("标题跟着文件名走，否则用户以为没生效").isEqualTo("schedule-v2");
        assertThat(updated.charCount()).isGreaterThan(original.charCount());
        // 不变量的核心：文档记录的分块数与向量表条数一致（SC-001）
        assertThat(inspector.countByDocumentId(original.id())).isEqualTo(updated.chunkCount());
    }

    @Test
    @DisplayName("旧内容的向量被彻底清掉：检索不到上一版才有的措辞")
    void clearsStaleVectors() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));
        documentService.update(original.id(), "schedule-v2.md", REPLACED.getBytes(StandardCharsets.UTF_8));

        // 「周三」只出现在旧版；确定性嵌入按词面匹配，能干净地区分两版
        boolean staleStillVisible = retrievalService.search("周三 数学课 王老师", 5).stream()
                .anyMatch(hit -> hit.documentId().equals(original.id()) && hit.content().contains("周三"));
        assertThat(staleStillVisible).as("旧版内容不该还能被检索到").isFalse();

        boolean freshVisible = retrievalService.search("周四 物理课 李老师", 5).stream()
                .anyMatch(hit -> hit.documentId().equals(original.id()));
        assertThat(freshVisible).as("新版内容应当可检索").isTrue();
    }
}
