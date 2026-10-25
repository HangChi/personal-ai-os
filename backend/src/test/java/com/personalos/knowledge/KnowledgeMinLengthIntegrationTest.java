package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 入库长度下限（2026-10-25 评测后新增）。
 *
 * <p>动机见 {@code scripts/eval/README.md}：29 / 92 字符的样例文档会因为向量信号
 * 过于「集中」而挤进检索前 5，把正确文档挤出榜。源头拒收比在检索端过滤更干净。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false"
})
@Testcontainers
class KnowledgeMinLengthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new DeterministicEmbeddingModel(1024);
        }
    }

    private final HttpTestClient http;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeProperties properties;

    @Autowired
    KnowledgeMinLengthIntegrationTest(@Value("${local.server.port}") int port,
                                      JdbcTemplate jdbcTemplate,
                                      KnowledgeDocumentService documentService,
                                      KnowledgeProperties properties) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
        this.properties = properties;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE knowledge_document, vector_store RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("低于下限的文档被拒收，并给出可读文案")
    void shouldRejectTooShortDocument() throws Exception {
        String tiny = "太短了";

        HttpTestClient.Response response = http.upload("/api/v1/knowledge/documents",
                "tiny.md", tiny.getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("无法形成有效分块");
        // 不留下文档记录：上传失败应当是「什么都没发生」
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_document", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("刚好达到下限的文档可以入库")
    void shouldAcceptDocumentAtThreshold() {
        int threshold = properties.minChunkSizeChars();
        StringBuilder content = new StringBuilder("# 边界文档\n\n");
        while (content.length() < threshold + 10) {
            content.append("这是一段用于凑够长度下限的正文内容。");
        }

        KnowledgeDocumentDto created = documentService.ingest(
                "boundary.md", content.toString(), content.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(created.charCount()).isGreaterThanOrEqualTo(threshold);
        assertThat(created.chunkCount()).isPositive();
    }
}
