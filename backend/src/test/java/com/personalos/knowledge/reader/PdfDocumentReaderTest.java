package com.personalos.knowledge.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * PDF 解析器单测：用 PDFBox **现场生成** PDF，不依赖测试资源文件。
 *
 * <p>用英文正文：PDF 里的中文需要嵌入字体，测试代码为字体问题服务没有意义 ——
 * 本类要验证的是「文本层能不能取出来」，不是字体渲染。
 */
class PdfDocumentReaderTest {

    private static final Map<String, Object> METADATA = Map.of("document_id", "doc-1");

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

    private static byte[] emptyPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("提取文本层，并附加元数据")
    void extractsTextLayer() throws Exception {
        byte[] bytes = pdfWithText("PostgreSQL transaction isolation levels");

        List<Document> documents = new PdfDocumentReader(bytes, METADATA).get();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("PostgreSQL transaction isolation levels");
        assertThat(documents.get(0).getMetadata()).containsEntry("document_id", "doc-1");
    }

    @Test
    @DisplayName("扫描版（无文本层）→ 明确报「未提取到文本」，而不是入库一条空文档")
    void emptyTextLayerIsRejected() throws Exception {
        byte[] bytes = emptyPdf();

        assertThatThrownBy(() -> new PdfDocumentReader(bytes, METADATA).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");
    }

    @Test
    @DisplayName("损坏 / 不是 PDF → 归一为可读文案，不泄漏异常类名")
    void brokenPdfIsTranslated() {
        byte[] broken = "this is definitely not a pdf".getBytes();

        assertThatThrownBy(() -> new PdfDocumentReader(broken, METADATA).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF");
    }
}
