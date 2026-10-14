package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.VectorStoreInspector;
import com.personalos.support.DeterministicEmbeddingModel;
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
 * 维度不一致的守卫测试（SC-007 / FR-014）。
 *
 * <p>单独一个测试类而不是塞进 {@code KnowledgeRagIntegrationTest}，是因为它需要**另一个 Spring 上下文**：
 * 这里注入的是 768 维的嵌入模型，而表结构固定为 {@code vector(1024)}。
 * {@code @SpringBootTest} 的上下文按配置缓存，两者不能共用。
 *
 * <p>这个用例守住的是本阶段最容易踩、也最难排查的坑：
 * 维度不一致在 pgvector 侧只在**写入那一刻**才报错，而且报的是原始 SQL 错误
 * （{@code expected 1024 dimensions, not 768}），看不出该改配置还是该改迁移。
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Testcontainers
class EmbeddingDimensionMismatchIntegrationTest {

    /** 故意与表结构（1024）不一致 */
    private static final int MISMATCHED_DIMENSIONS = 768;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class MismatchedEmbeddingConfiguration {
        @Bean
        EmbeddingModel mismatchedEmbeddingModel() {
            return new DeterministicEmbeddingModel(MISMATCHED_DIMENSIONS);
        }
    }

    @Autowired
    KnowledgeDocumentService documentService;
    @Autowired
    KnowledgeDocumentRepository documentRepository;
    @Autowired
    VectorStoreInspector inspector;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM vector_store");
        documentRepository.deleteAll();
    }

    @Test
    @DisplayName("用例9：嵌入维度与表结构不一致 → 报错含「维度」、且两张表零写入（SC-007）")
    void mismatchedEmbeddingDimensionIsRejectedWithZeroWrites() {
        // 前置：表确实是 1024 维（启动期校验只比对"表 ↔ 配置"，两者都是 1024，因此启动成功）
        assertThat(inspector.embeddingColumnType().orElseThrow()).contains("1024");

        byte[] content = "# 维度测试\n\n这段内容会触发一次真实写入尝试。".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> documentService.ingest("dimension.md", "维度测试", content))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_DIMENSION_MISMATCH)
                .hasMessageContaining("维度");

        // 关键：失败必须干净 —— 文档记录不能留下半成品，向量更不能写入一部分
        assertThat(documentRepository.count()).as("失败时不应留下文档记录").isZero();
        assertThat(inspector.countAll()).as("失败时不应写入任何向量").isZero();
    }
}
