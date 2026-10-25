package com.personalos.knowledge.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * docx 解析器单测：用 POI 现场生成 docx。
 *
 * <p>重点验证**表格内容也被提取**。只取段落是最容易犯的错，而且它的症状是
 * 「上传成功、检索不到表格里的内容」，不会报任何错 —— 所以必须有这条断言。
 */
class DocxDocumentReaderTest {

    private static final Map<String, Object> METADATA = Map.of("document_id", "doc-2");

    private static byte[] docxWithParagraphAndTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("第一段：数据库事务的隔离级别");
            document.createParagraph().createRun().setText("第二段：默认是读已提交");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("隔离级别");
            table.getRow(0).getCell(1).setText("脏读");
            table.getRow(1).getCell(0).setText("可重复读");
            table.getRow(1).getCell(1).setText("不可能");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("段落与表格单元格都进入文本，且保持文档顺序")
    void extractsParagraphsAndTables() throws Exception {
        byte[] bytes = docxWithParagraphAndTable();

        List<Document> documents = new DocxDocumentReader(bytes, METADATA).get();
        String text = documents.get(0).getText();

        assertThat(text).contains("第一段：数据库事务的隔离级别");
        assertThat(text).contains("第二段：默认是读已提交");
        // 表格里的内容 —— 只取 getParagraphs() 的实现会在这里失败
        assertThat(text).contains("隔离级别");
        assertThat(text).contains("可重复读");
        // 段落顺序在表格之前
        assertThat(text.indexOf("第一段")).isLessThan(text.indexOf("隔离级别"));
    }

    @Test
    @DisplayName("空文档 → 明确报「未提取到文本」")
    void emptyDocumentIsRejected() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            bytes = out.toByteArray();
        }

        assertThatThrownBy(() -> new DocxDocumentReader(bytes, METADATA).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");
    }

    @Test
    @DisplayName("损坏 / 不是 docx → 归一为可读文案")
    void brokenDocxIsTranslated() {
        byte[] broken = "not a docx at all".getBytes();

        assertThatThrownBy(() -> new DocxDocumentReader(broken, METADATA).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Word");
    }
}
