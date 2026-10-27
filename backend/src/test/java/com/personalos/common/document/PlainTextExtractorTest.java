package com.personalos.common.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯文本抽取器单测。
 *
 * <p>PDF 样本用 PDFBox **现场生成**，不依赖测试资源文件（与 {@code PdfDocumentReaderTest} 同一手法）。
 * 正文用英文：PDF 的中文需要嵌字体，而这里要验的是「文本层能不能取出来」。
 */
class PlainTextExtractorTest {

    private final PlainTextExtractor extractor = new PlainTextExtractor();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

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

    @Test
    @DisplayName("text/plain 直接按 UTF-8 读出")
    void readsPlainText() {
        Optional<PlainTextExtractor.Extracted> result =
                extractor.extract(bytes("这是一段说明"), "note.txt", "text/plain");

        assertThat(result).isPresent();
        assertThat(result.get().text()).isEqualTo("这是一段说明");
        assertThat(result.get().truncated()).isFalse();
    }

    @Test
    @DisplayName("浏览器给 octet-stream 时退化到后缀判断（.md / .json 仍能读）")
    void fallsBackToExtension() {
        assertThat(extractor.extract(bytes("# 标题"), "readme.md", "application/octet-stream"))
                .isPresent();
        assertThat(extractor.extract(bytes("{\"a\":1}"), "data.json", null))
                .isPresent();
    }

    @Test
    @DisplayName("PDF 取文本层")
    void readsPdfTextLayer() throws Exception {
        Optional<PlainTextExtractor.Extracted> result =
                extractor.extract(pdfWithText("Hello attachment"), "doc.pdf", "application/pdf");

        assertThat(result).isPresent();
        assertThat(result.get().text()).contains("Hello attachment");
    }

    @Test
    @DisplayName("不在白名单里的类型返回 empty，而不是抛异常（用户传 zip 不该让对话失败）")
    void unsupportedTypeYieldsEmpty() {
        assertThat(extractor.extract(bytes("PK\u0003\u0004"), "archive.zip", "application/zip")).isEmpty();
        assertThat(extractor.extract(bytes("x"), "photo.png", "image/png"))
                .as("图片走多模态，不走文本抽取")
                .isEmpty();
    }

    @Test
    @DisplayName("超过上限时截断并标记 —— 让模型知道「后面还有，只是没给你」")
    void truncatesOverlongText() {
        String longText = "字".repeat(PlainTextExtractor.MAX_CHARS + 500);

        Optional<PlainTextExtractor.Extracted> result =
                extractor.extract(bytes(longText), "big.txt", "text/plain");

        assertThat(result).isPresent();
        assertThat(result.get().truncated()).isTrue();
        assertThat(result.get().text()).hasSize(PlainTextExtractor.MAX_CHARS);
    }

    @Test
    @DisplayName("空白内容与损坏的 PDF 都按「读不到」处理")
    void blankOrBrokenYieldsEmpty() {
        assertThat(extractor.extract(bytes("   \n\t "), "empty.txt", "text/plain")).isEmpty();
        assertThat(extractor.extract(bytes("not a real pdf"), "broken.pdf", "application/pdf")).isEmpty();
        assertThat(extractor.extract(new byte[0], "zero.txt", "text/plain")).isEmpty();
    }
}
