package com.personalos.knowledge.reader;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

/**
 * 图片解析器：把图片交给视觉模型转文字，再走与其它格式完全相同的归一逻辑。
 *
 * <p>本类只负责「取文本 + 归一 + 附加元数据」；识别质量取决于模型，不由本系统保证。
 */
public class ImageDocumentReader implements DocumentReader {

    private final byte[] bytes;
    private final String mimeType;
    private final Map<String, Object> metadata;
    private final VisionTextExtractor extractor;

    public ImageDocumentReader(byte[] bytes, String mimeType,
                               Map<String, Object> metadata, VisionTextExtractor extractor) {
        this.bytes = bytes;
        this.mimeType = mimeType;
        this.metadata = metadata;
        this.extractor = extractor;
    }

    @Override
    public List<Document> get() {
        return ReaderSupport.toDocuments(extractor.extract(bytes, mimeType), metadata);
    }
}
