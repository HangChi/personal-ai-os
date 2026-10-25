package com.personalos.knowledge.reader;

import java.util.Map;
import java.util.Set;
import org.springframework.ai.document.DocumentReader;
import org.springframework.stereotype.Component;

/**
 * 按扩展名分派到对应的解析器。
 *
 * <h2>为什么单独抽一个工厂</h2>
 * 三种格式的**失败语义完全不同**（加密 PDF / 损坏 docx / 模型不可用），
 * 这些细节不该出现在 {@code KnowledgeDocumentService} 的编排逻辑里 ——
 * 那个类已经承担了校验、落库、分块、向量化、失败翻译五段职责。
 *
 * <p>同样，也不该把它们塞进 {@code KnowledgeDocumentReader}：那个类的职责是「文本解码」
 * （严格 UTF-8 校验、去 BOM），让它同时懂 PDF 与视觉模型，等于把一个类变成三种技术栈的入口。
 */
@Component
public class DocumentReaderFactory {

    /** 走视觉模型的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final VisionTextExtractor visionTextExtractor;

    public DocumentReaderFactory(VisionTextExtractor visionTextExtractor) {
        this.visionTextExtractor = visionTextExtractor;
    }

    /**
     * @param extension   小写扩展名（不含点）
     * @param contentType 已判定的 MIME 类型（图片解析需要它）
     * @param bytes       原始字节
     * @param metadata    附加到片段的元数据
     * @return 对应的解析器；未知扩展名按纯文本处理（白名单已在 Service 层拦过）
     */
    public DocumentReader create(String extension, String contentType,
                                 byte[] bytes, Map<String, Object> metadata) {
        if ("pdf".equals(extension)) {
            return new PdfDocumentReader(bytes, metadata);
        }
        if ("docx".equals(extension)) {
            return new DocxDocumentReader(bytes, metadata);
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return new ImageDocumentReader(bytes, contentType, metadata, visionTextExtractor);
        }
        return new KnowledgeDocumentReader(bytes, metadata);
    }
}
