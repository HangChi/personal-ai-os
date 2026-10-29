package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.service.DocumentWriteLock;
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
    private DocumentWriteLock writeLock;

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

    @Test
    @DisplayName("向量里的标题与文件名跟着新文件走：否则引用永远显示旧名字")
    void refreshesVectorMetadata() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));
        documentService.update(original.id(), "schedule-v2.md", REPLACED.getBytes(StandardCharsets.UTF_8));

        // 元数据是在解析前固化的：曾经用「更新前的实体」构造，
        // 于是向量里留着旧标题，检索引用和 ask 的来源名永远显示旧名 ——
        // 而且没有任何入口能修正（除非再传一次同名文件）。
        assertThat(jdbc.queryForObject(
                "select distinct metadata->>'document_title' from vector_store", String.class))
                .as("向量元数据里的标题必须是新标题")
                .isEqualTo("schedule-v2");
        assertThat(jdbc.queryForObject(
                "select distinct metadata->>'filename' from vector_store", String.class))
                .as("向量元数据里的文件名必须是新文件名")
                .isEqualTo("schedule-v2.md");
    }

    @Test
    @DisplayName("超长文件名不会把更新打成 500：列宽内截断并保住扩展名")
    void truncatesOverlongFilename() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));

        // 列宽 300。不截断时插入抛 DataIntegrityViolation，
        // 用户看到的是「服务器内部错误」——而真实原因只是名字太长。
        String longName = "很长的名字".repeat(80) + ".md"; // 400 + 3 字符

        KnowledgeDocumentDto updated = documentService.update(
                original.id(), longName, REPLACED.getBytes(StandardCharsets.UTF_8));

        assertThat(updated.filename().length()).isLessThanOrEqualTo(300);
        assertThat(updated.filename()).as("扩展名必须保住，它决定用哪个解析器").endsWith(".md");
        assertThat(updated.title().length()).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("同一文档正在写入时，替换被拒（不同时改两代向量）")
    void rejectsConcurrentUpdate() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));

        // 手动持锁模拟「另一个请求正在替换」：真起两个线程去撞几十秒的窗口太不稳定，
        // 而这里要验的是**接线**——update 到底有没有去抢锁、异常路径有没有释放。
        writeLock.acquire(original.id());
        try {
            assertThatThrownBy(() -> documentService.update(
                    original.id(), "v2.md", REPLACED.getBytes(StandardCharsets.UTF_8)))
                    .as("并发替换必须在入口被拒，而不是两个请求各写一代向量")
                    .isInstanceOf(BusinessException.class);
        } finally {
            writeLock.release(original.id());
        }

        // 释放后必须能正常更新：证明拒绝确实来自锁，且锁没有把文档永久锁死
        assertThat(documentService.update(
                original.id(), "schedule-v2.md", REPLACED.getBytes(StandardCharsets.UTF_8)).filename())
                .isEqualTo("schedule-v2.md");
    }

    @Test
    @DisplayName("删除与替换共用同一把锁：替换进行中不能删")
    void deleteRespectsSameLock() {
        KnowledgeDocumentDto original = documentService.ingest(
                "schedule.md", "排课说明", ORIGINAL.getBytes(StandardCharsets.UTF_8));

        writeLock.acquire(original.id());
        try {
            assertThatThrownBy(() -> documentService.delete(original.id()))
                    .as("删除也清旧向量，必须与替换互斥")
                    .isInstanceOf(BusinessException.class);
        } finally {
            writeLock.release(original.id());
        }

        assertThat(documentService.delete(original.id())).isGreaterThan(0);
    }
}
