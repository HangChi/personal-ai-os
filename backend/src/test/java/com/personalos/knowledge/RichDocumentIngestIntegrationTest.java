package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
 * 富文档入库的集成测试：PDF 与 docx 走**完整的**入库链路
 * （解析 → 分块 → 真实 pgvector 写入 → 检索命中）。
 *
 * <h2>为什么不能只靠解析器单测</h2>
 * 单测只证明「字节能变成文本」。真正会出问题的是后半段：分块是否切坏了内容、
 * 向量是否写进去了、检索能否命中。这些必须走真实向量库才看得见。
 *
 * <p>嵌入模型换成词袋桩（与阶段 3 同一套做法）：因此「语义相似」无法验证，
 * 但**链路正确性**可以 —— 查询词与文档词面重叠时必然命中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        // 词袋相似度是词面重叠的线性映射，阈值置 0 只验证排序与召回
        "app.knowledge.similarity-threshold=0.0"
})
@Testcontainers
class RichDocumentIngestIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new DeterministicEmbeddingModel(1024);
        }

        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeRetrievalService retrievalService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    RichDocumentIngestIntegrationTest(KnowledgeDocumentService documentService,
                                      KnowledgeDocumentRepository documentRepository,
                                      KnowledgeRetrievalService retrievalService,
                                      JdbcTemplate jdbcTemplate) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM vector_store");
        documentRepository.deleteAll();
    }

    // ------------------------------------------------------------------ PDF

    @Test
    @DisplayName("上传含文本层的 PDF：字符数与片段数均大于 0，检索能命中")
    void pdfIsIngestedAndRetrievable() throws Exception {
        byte[] pdf = pdfWithText("PostgreSQL supports four transaction isolation levels");

        var document = documentService.ingest("lecture.pdf", "数据库讲义", pdf);

        assertThat(document.contentType()).isEqualTo("application/pdf");
        assertThat(document.charCount()).isGreaterThan(0);
        assertThat(document.chunkCount()).isGreaterThan(0);

        List<KnowledgeHitDto> hits = retrievalService.search("transaction isolation levels", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).content()).contains("transaction isolation");
    }

    @Test
    @DisplayName("扫描版 PDF（无文本层）→ 40003，且文档表零写入")
    void scannedPdfIsRejectedWithoutWrites() throws Exception {
        byte[] blank = blankPdf();

        assertThatThrownBy(() -> documentService.ingest("scan.pdf", null, blank))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");

        assertThat(documentRepository.count()).isZero();
        assertThat(vectorCount()).isZero();
    }

    // ------------------------------------------------------------------ docx

    @Test
    @DisplayName("上传 docx：表格内容也进了索引（检索能命中表格里的词）")
    void docxTablesAreIndexed() throws Exception {
        byte[] docx = docxWithTable();

        var document = documentService.ingest("lesson.docx", "教案", docx);

        assertThat(document.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(document.charCount()).isGreaterThan(0);

        // 「可重复读」只出现在表格单元格里 —— 只取段落的实现会在这里检索不到
        List<KnowledgeHitDto> hits = retrievalService.search("可重复读", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).content()).contains("可重复读");
    }

    @Test
    @DisplayName("不支持的扩展名仍被白名单拒绝")
    void unsupportedExtensionIsRejected() {
        assertThatThrownBy(() -> documentService.ingest("slides.pptx", null, new byte[]{1, 2, 3}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    // ------------------------------------------------------------------ 构造样本

    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("课时安排：本单元共四课时");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("隔离级别");
            table.getRow(0).getCell(1).setText("可重复读");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private long vectorCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        return count == null ? 0 : count;
    }
}
