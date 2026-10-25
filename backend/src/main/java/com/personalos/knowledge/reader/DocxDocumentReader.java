package com.personalos.knowledge.reader;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

/**
 * Word（.docx）解析器：提取段落与**表格单元格**文本。
 *
 * <h2>为什么遍历 body 元素而不是只取段落</h2>
 * 教案、课件的正文经常一半在段落、一半在表格里（课时表、对照表）。
 * 只调 {@code getParagraphs()} 会静默丢掉整张表 —— 用户看到的是一次成功上传，
 * 但知识库里少了半篇内容，而且没有任何迹象。
 */
public class DocxDocumentReader implements DocumentReader {

    private final byte[] bytes;
    private final Map<String, Object> metadata;

    public DocxDocumentReader(byte[] bytes, Map<String, Object> metadata) {
        this.bytes = bytes;
        this.metadata = metadata;
    }

    @Override
    public List<Document> get() {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : docx.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendLine(text, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    appendTable(text, table);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // POI 对损坏文件会抛 IOException 或 IllegalStateException/POIXMLException 等运行时异常
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "Word 解析失败：文件可能已损坏，或不是有效的 .docx（旧版 .doc 暂不支持）");
        }
        return ReaderSupport.toDocuments(text.toString(), metadata);
    }

    private void appendTable(StringBuilder text, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder();
            for (XWPFTableCell cell : row.getTableCells()) {
                // 同一行的单元格用制表符分隔：分块后仍能看出「这是一行的几列」
                if (line.length() > 0) {
                    line.append('\t');
                }
                line.append(joinParagraphs(cell));
            }
            appendLine(text, line.toString());
        }
    }

    private String joinParagraphs(XWPFTableCell cell) {
        StringBuilder cellText = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String value = paragraph.getText();
            if (value != null && !value.isBlank()) {
                if (cellText.length() > 0) {
                    cellText.append(' ');
                }
                cellText.append(value.strip());
            }
        }
        return cellText.toString();
    }

    private void appendLine(StringBuilder text, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        text.append(line.strip()).append('\n');
    }
}
