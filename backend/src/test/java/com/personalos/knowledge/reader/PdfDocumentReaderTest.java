package com.personalos.knowledge.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
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

        List<Document> documents = new PdfDocumentReader(bytes, METADATA,
                (image, mime) -> { throw new AssertionError("text page should not use vision"); }).get();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("PostgreSQL transaction isolation levels");
        assertThat(documents.get(0).getMetadata()).containsEntry("document_id", "doc-1");
    }

    @Test
    @DisplayName("空白 PDF（无文本层）→ 明确报「未提取到文本」，而不是入库一条空文档")
    void emptyTextLayerIsRejected() throws Exception {
        byte[] bytes = emptyPdf();

        assertThatThrownBy(() -> new PdfDocumentReader(bytes, METADATA, null).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");
    }

    @Test
    @DisplayName("损坏 / 不是 PDF → 归一为可读文案，不泄漏异常类名")
    void brokenPdfIsTranslated() {
        byte[] broken = "this is definitely not a pdf".getBytes();

        assertThatThrownBy(() -> new PdfDocumentReader(broken, METADATA, null).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF");
    }

    private static byte[] pdfWithPages(String... contents) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            for (String value : contents) {
                PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(120, 120));
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    if (value.equals("SCAN")) {
                        BufferedImage scan = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = scan.createGraphics();
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, 80, 80);
                        graphics.setColor(Color.BLACK);
                        graphics.drawString("scan text", 5, 40);
                        graphics.dispose();
                        content.drawImage(LosslessFactory.createFromImage(pdf, scan), 10, 10, 80, 80);
                    } else {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(10, 70);
                        content.showText(value);
                        content.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void scannedAndMixedPagesKeepOrder() throws Exception {
        byte[] pdf = pdfWithPages("first page", "SCAN", "last page");
        int[] calls = {0};
        VisionTextExtractor vision = (image, mime) -> {
            calls[0]++;
            assertThat(mime).isEqualTo("image/jpeg");
            assertThat(image).startsWith((byte) 0xff, (byte) 0xd8);
            return "scanned middle page";
        };
        String text = new PdfDocumentReader(pdf, METADATA, vision).get().get(0).getText();
        assertThat(text).contains("first page\n\nscanned middle page\n\nlast page");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(new PdfDocumentReader(pdfWithPages("SCAN"), METADATA, vision)
                .get().get(0).getText()).isEqualTo("scanned middle page");
    }

    @Test
    void visionFailureAndLimitsAreReported() throws Exception {
        byte[] scan = pdfWithPages("SCAN");
        assertThatThrownBy(() -> new PdfDocumentReader(scan, METADATA, null).get())
                .isInstanceOf(BusinessException.class).hasMessageContaining("视觉模型");
        assertThatThrownBy(() -> new PdfDocumentReader(scan, METADATA, (image, mime) -> "").get())
                .isInstanceOf(BusinessException.class).hasMessageContaining("未提取到文本");
        BusinessException upstream = new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "upstream");
        assertThatThrownBy(() -> new PdfDocumentReader(scan, METADATA,
                (image, mime) -> { throw upstream; }).get()).isSameAs(upstream);
        String[] pages = new String[21];
        java.util.Arrays.fill(pages, "SCAN");
        assertThatThrownBy(() -> new PdfDocumentReader(pdfWithPages(pages), METADATA,
                (image, mime) -> "recognized").get())
                .isInstanceOf(BusinessException.class).hasMessageContaining("20 页");
    }
}
