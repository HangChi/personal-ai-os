package com.personalos.knowledge.reader;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * 各格式解析器共用的小工具。
 *
 * <h2>为什么空文本必须报错</h2>
 * 扫描版 PDF、纯图片、损坏的 docx 都会「解析成功但得到空字符串」。
 * 若把它当成正常结果入库，文档列表里会出现一条字符数为 0、永远检索不到的记录 ——
 * 用户看到「上传成功」，却怎么都搜不到，这类静默失败比直接报错难排查得多。
 *
 * <p>因此空结果统一转成 {@code UNSUPPORTED_DOCUMENT}，并给出**可操作**的解释
 * （是扫描件、需要 OCR）。
 */
final class ReaderSupport {

    private ReaderSupport() {
    }

    /**
     * 归一提取到的文本并包装成 {@link Document}。
     *
     * @param text     解析器提取的原始文本
     * @param metadata 需要附加到片段上的元数据
     * @return 单个文档（分块由上层统一处理）
     * @throws BusinessException 文本为空或只有空白时
     */
    static List<Document> toDocuments(String text, Map<String, Object> metadata) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "未提取到文本（如果是扫描件或纯图片，需要 OCR，当前未支持）");
        }
        return List.of(new Document(normalized, metadata));
    }

    /** 去掉 BOM 与首尾空白；{@code null} 视为空串 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\uFEFF", "").strip();
    }
}
