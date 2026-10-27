package com.personalos.common.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「把文件变成一段纯文本」的通用能力。
 *
 * <h2>为什么放在 common 而不是 chat</h2>
 * 文档抽取不是对话的领域知识：知识库要它做切分、对话要它做附件理解。
 * 放在业务模块里，第二个使用方就得复制一遍或产生反向依赖。
 *
 * <h2>与知识库 reader 的区别</h2>
 * 知识库的 {@code PdfDocumentReader} / {@code DocxDocumentReader} 实现 Spring AI 的
 * {@code DocumentReader}，产出的是**带元数据的 Document**（要参与切分与检索）。
 * 这里只需要一段文本，签名简单得多。两者刻意不合并：硬凑成一个会同时迁就两边的需求。
 *
 * <h2>不支持的类型返回 empty 而不是抛异常</h2>
 * 调用方的语义是「能读就带上，读不了就跳过」—— 用户传了个 zip 不该让整轮对话失败。
 */
@Component
public class PlainTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PlainTextExtractor.class);

    /**
     * 抽取结果的字符上限。
     *
     * <p>8000 字 ≈ 5k token：单份附件占掉这么多已经不小，再多会挤掉对话历史与知识库片段。
     * 超限时截断并在文本尾部注明 —— 让模型知道「后面还有，只是没给你」，
     * 否则它会把不完整的结论当成完整结论来答。
     */
    public static final int MAX_CHARS = 8000;

    /** 抽取结果 */
    public record Extracted(String text, boolean truncated) {
    }

    /**
     * 尝试把文件抽成文本。
     *
     * @param content     文件字节
     * @param filename    原始文件名（用于判断后缀，可能为 null）
     * @param contentType MIME 类型（可能为 null 或 application/octet-stream）
     * @return 抽取成功返回文本；不支持或解析失败返回 empty（**不抛异常**）
     */
    public Optional<Extracted> extract(byte[] content, String filename, String contentType) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        String text;
        try {
            if (isPlainText(mime, name)) {
                text = new String(content, StandardCharsets.UTF_8);
            } else if (mime.equals("application/pdf") || name.endsWith(".pdf")) {
                text = pdfText(content);
            } else if (name.endsWith(".docx") || mime.contains("wordprocessingml")) {
                text = docxText(content);
            } else {
                // 图片走多模态（不进这里）、其余二进制格式明确不支持
                return Optional.empty();
            }
        } catch (IOException | RuntimeException ex) {
            // 解析失败只降级为「读不到内容」，用户仍能看到附件本身
            log.warn("附件文本抽取失败，按无内容处理 filename={} contentType={}", filename, contentType, ex);
            return Optional.empty();
        }

        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.length() > MAX_CHARS) {
            return Optional.of(new Extracted(normalized.substring(0, MAX_CHARS), true));
        }
        return Optional.of(new Extracted(normalized, false));
    }

    /** 纯文本判定：MIME 优先，退化到后缀（浏览器有时给 octet-stream） */
    private boolean isPlainText(String mime, String name) {
        if (mime.startsWith("text/")) {
            return true;
        }
        if (mime.equals("application/json") || mime.equals("application/xml")
                || mime.equals("application/yaml") || mime.equals("application/x-yaml")) {
            return true;
        }
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".json")
                || name.endsWith(".csv") || name.endsWith(".log") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".xml");
    }

    private String pdfText(byte[] content) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    private String docxText(byte[] content) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (XWPFParagraph paragraph : docx.getParagraphs()) {
                builder.append(paragraph.getText()).append('\n');
            }
        }
        return builder.toString();
    }
}
